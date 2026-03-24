package org.parchmentmc.tasks.unpick

import daomephsta.unpick.api.ValidatingUnpickV3Visitor
import daomephsta.unpick.api.classresolvers.ClassResolvers
import daomephsta.unpick.api.classresolvers.IClassResolver
import daomephsta.unpick.constantmappers.datadriven.parser.UnpickSyntaxException
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Reader
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.parchmentmc.util.path
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import javax.inject.Inject
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.exists
import kotlin.io.path.name

abstract class CheckUnpickDefinitions : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:CompileClasspath
    abstract val classpath: ConfigurableFileCollection

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun run() {
        val workQueue = workerExecutor.noIsolation()

        workQueue.submit(CheckAction::class) {
            input.set(this@CheckUnpickDefinitions.input)
            classpath.from(this@CheckUnpickDefinitions.classpath)
        }
    }

    interface CheckParameters : WorkParameters {
        val input: DirectoryProperty
        val classpath: ConfigurableFileCollection
    }

    abstract class CheckAction : WorkAction<CheckParameters> {

        @OptIn(ExperimentalAtomicApi::class)
        override fun execute() {
            val classpathJars = mutableListOf<FileSystem>()

            try {
                val classResolvers = mutableListOf<IClassResolver>()

                for (file in parameters.classpath.files) {
                    val fileSystem = FileSystems.newFileSystem(file.toPath())
                    classpathJars.add(fileSystem)
                    classResolvers.add(ClassResolvers.fromDirectory(fileSystem.rootDirectories.first()))
                }

                classResolvers.add(ClassResolvers.classpath(null))

                val classResolver = combineClassResolver(classResolvers)
                val failureCount = AtomicInt(0)

                parameters.input.asFileTree.files.parallelStream().forEach { file ->
                    if (file.isDirectory || !file.name.endsWith(".unpick")) {
                        return@forEach
                    }

                    val errors = mutableListOf<UnpickSyntaxException>()
                    try {
                        validateUnpickFile(file, classResolver, classpathJars, errors)
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }

                    if (errors.isNotEmpty()) {
                        val relativePath = parameters.input.path.relativize(file.toPath())
                        errors.forEach { e ->
                            System.err.println("$relativePath: ${e.message}")
                        }
                        failureCount.addAndFetch(errors.size)
                    }
                }

                failureCount.load().takeIf { it != 0 }?.also { errors ->
                    throw UnpickSyntaxException("There were $errors unpick check failures, see prior log messages for details")
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            } finally {
                try {
                    for (classpathJar in classpathJars) {
                        classpathJar.close()
                    }
                } catch (_: IOException) {
                    // ignore
                }
            }
        }

        @Throws(IOException::class)
        private fun validateUnpickFile(
            file: File,
            classResolver: IClassResolver,
            classpathJars: MutableList<FileSystem>,
            errors : MutableList<UnpickSyntaxException>
        ) {
            UnpickV3Reader(FileReader(file)).use { reader ->
                val visitor = object : ValidatingUnpickV3Visitor(classResolver) {
                    override fun packageExists(packageName: String): Boolean {
                        return packageExists(classpathJars, packageName)
                    }
                }
                reader.accept(visitor)
                errors.addAll(visitor.finishValidation())
            }
        }

        companion object {
            @JvmStatic
            private fun combineClassResolver(classResolvers: MutableList<IClassResolver>): IClassResolver {
                if (classResolvers.isEmpty()) {
                    throw IllegalArgumentException("classResolvers cannot be empty")
                }

                var result = classResolvers[0]
                for (i in 1..<classResolvers.size) {
                    result = result.chain(classResolvers[i])
                }

                return result
            }

            @JvmStatic
            private fun packageExists(classpathJars: MutableList<FileSystem>, packageName: String): Boolean {
                val packageDir = packageName.replace('.', '/') + "/"

                for (classpathJar in classpathJars) {
                    val packagePath = classpathJar.getPath(packageDir)

                    if (packagePath.exists()) {
                        try {
                            Files.list(packagePath).use { subFiles ->
                                if (subFiles.anyMatch { file -> file.name.endsWith(".class") }) {
                                    return true
                                }
                            }
                        } catch (e: IOException) {
                            throw UncheckedIOException(e)
                        }
                    }
                }

                return false
            }
        }
    }
}
