package org.parchmentmc.tasks

import cuchaz.enigma.ProgressListener
import cuchaz.enigma.translation.mapping.EntryMapping
import cuchaz.enigma.translation.mapping.serde.MappingFileNameFormat
import cuchaz.enigma.translation.mapping.serde.MappingFormat
import cuchaz.enigma.translation.mapping.serde.MappingParseException
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters
import cuchaz.enigma.translation.mapping.tree.EntryTree
import cuchaz.enigma.translation.mapping.tree.HashEntryTree
import cuchaz.enigma.translation.representation.entry.ClassEntry
import cuchaz.enigma.translation.representation.entry.Entry
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry
import cuchaz.enigma.translation.representation.entry.MethodEntry
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.kotlin.dsl.submit
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.parchmentmc.util.JavadocsChecker
import org.parchmentmc.util.path
import java.io.IOException

abstract class JavadocLint : IncrementalDataTask() {

    override fun execute(changes: InputChanges) {
        val workQueue = workerExecutor.noIsolation()

        workQueue.submit(LintAction::class) {
            inputMapping.set(this@JavadocLint.inputMapping)
        }
    }

    interface LintParams : WorkParameters {
        val inputMapping: DirectoryProperty
    }

    abstract class LintAction : WorkAction<LintParams> {

        private fun getFullName(mappings: EntryTree<EntryMapping>, entry: Entry<*>): String? {
            var name = if (entry is MethodEntry) {
                entry.name + ' ' +  entry.desc.toString()
            } else if (entry is ClassEntry) {
                entry.name
            } else {
                mappings.get(entry)?.targetName()
            }

            if (entry.parent != null) {
                name = getFullName(mappings, entry.parent!!) + '.' + name
            }

            return name
        }

        override fun execute() {
            try {
                val mappings = HashEntryTree<EntryMapping>()
                val tree = MappingFormat.ENIGMA_DIRECTORY.read(
                    parameters.inputMapping.path,
                    ProgressListener.none(),
                    MappingSaveParameters(MappingFileNameFormat.BY_DEOBF),
                    null
                )

                tree.forEach { node ->
                    mappings.insert(node.entry, node.value)
                }

                val errors = mutableListOf<String>()

                mappings.allEntries.parallel().forEachOrdered { entry ->
                    val mapping = mappings[entry] ?: error("Mapping entry not found")
                    val javadoc = mapping.javadoc()
                    if (javadoc != null && javadoc.isNotEmpty()) {
                        val localErrors = mutableListOf<String>()

                        if (entry is LocalVariableEntry && entry.isArgument) {
                            JavadocsChecker.enforceParam(javadoc.lineSequence().toList()) { localErrors.add(it) }
                        } else if (entry is MethodEntry) {
                            JavadocsChecker.enforceMethod(javadoc.lineSequence().toList()) { localErrors.add(it) }
                        }

                        // new rules can be added here in the future
                        if (localErrors.isNotEmpty()) {
                            val name = getFullName(mappings, entry)

                            for (error in localErrors) {
                                errors.add("$name: $error")
                            }
                        }
                    }
                }

                if (errors.isNotEmpty()) {
                    for (error in errors) {
                        println("lint: $error")
                    }

                    error("Found ${errors.size} javadoc format errors! See the log for details.")
                }
            } catch (e: IOException) {
                throw GradleException("Could not read and parse mappings", e)
            } catch (e: MappingParseException) {
                throw GradleException("Could not read and parse mappings", e)
            }
        }
    }
}
