package org.parchmentmc.tasks.unpick

import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Reader
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Writer
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File
import java.io.FileReader

// todo remove once https://github.com/ParchmentMC/Compass/pull/28 is merged and released
abstract class GenerateUnpickV3Data : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val definitions: DirectoryProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val files = mutableListOf<File>()
        files.addAll(definitions.asFileTree.files)
        files.sortBy { file -> file.name }

        val writer = UnpickV3Writer()
        for (file in files) {
            if (!file.name.endsWith(".unpick")) {
                continue
            }

            UnpickV3Reader(FileReader(file)).use { reader ->
                reader.accept(writer)
            }
        }

        output.get().asFile.writeText(
            writer.output.replace(System.lineSeparator(), "\n"),
            Charsets.UTF_8
        )
    }
}
