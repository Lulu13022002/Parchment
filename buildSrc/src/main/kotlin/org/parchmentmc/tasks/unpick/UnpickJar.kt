package org.parchmentmc.tasks.unpick

import daomephsta.unpick.api.ConstantUninliner
import daomephsta.unpick.api.classresolvers.ClassResolvers
import daomephsta.unpick.api.constantgroupers.ConstantGroupers
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class UnpickJar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val input: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val definitions: RegularFileProperty

    @get:CompileClasspath
    abstract val classpath: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val outputFile = output.get().asFile
        outputFile.delete()

        val classpathZips = mutableListOf<ZipFile>()
        ZipFile(input.get().asFile).use { inputZip ->
            BufferedReader(FileReader(definitions.get().asFile)).use { mappingsReader ->
                ZipOutputStream(FileOutputStream(outputFile)).use { outputZip ->
                    try {
                        var classResolver = ClassResolvers.jar(inputZip)

                        for (file in classpath.files) {
                            val zip = ZipFile(file)
                            classpathZips.add(zip)
                            classResolver = classResolver.chain(ClassResolvers.jar(zip))
                        }

                        classResolver = classResolver.chain(ClassResolvers.classpath())

                        val uninliner = ConstantUninliner.builder()
                            .classResolver(classResolver)
                            .grouper(
                                ConstantGroupers.dataDriven()
                                    .classResolver(classResolver)
                                    .mappingSource(mappingsReader)
                                    .build()
                            )
                            .build()

                        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()).use { executor ->
                            val entryFutures = mutableListOf<CompletableFuture<PendingOutputEntry>>()
                            val inputEntries = inputZip.entries()

                            while (inputEntries.hasMoreElements()) {
                                val entry = inputEntries.nextElement()
                                entryFutures.add(CompletableFuture.supplyAsync({
                                    if (entry.isDirectory) {
                                        return@supplyAsync PendingOutputEntry(entry.name, null)
                                    } else if (!entry.name.endsWith(".class")) {
                                        return@supplyAsync PendingOutputEntry(
                                            entry.name,
                                            inputZip.getInputStream(entry).readAllBytes()
                                        )
                                    } else {
                                        val clazz = ClassNode()
                                        ClassReader(inputZip.getInputStream(entry)).accept(clazz, 0)
                                        uninliner.transform(clazz)
                                        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                                        clazz.accept(writer)
                                        return@supplyAsync PendingOutputEntry(entry.name, writer.toByteArray())
                                    }
                                }, executor))
                            }

                            for (entryFuture in entryFutures) {
                                val entry = entryFuture.join()
                                outputZip.putNextEntry(ZipEntry(entry.name))

                                if (entry.data != null) {
                                    outputZip.write(entry.data)
                                }

                                outputZip.closeEntry()
                            }
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    } finally {
                        for (classpathZip in classpathZips) {
                            try {
                                classpathZip.close()
                            } catch (_: IOException) {
                                // ignore
                            }
                        }
                    }
                }
            }
        }
    }

    data class PendingOutputEntry(val name: String, val data: ByteArray?) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PendingOutputEntry

            if (name != other.name) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}
