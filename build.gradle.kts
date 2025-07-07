import org.parchmentmc.compass.CompassPlugin
import org.parchmentmc.compass.data.validation.impl.MemberExistenceValidator
import org.parchmentmc.compass.data.validation.impl.MethodStandardsValidator
import org.parchmentmc.compass.tasks.GenerateExport
import org.parchmentmc.compass.tasks.GenerateSanitizedExport
import org.parchmentmc.compass.tasks.SanitizeData
import org.parchmentmc.compass.tasks.ValidateData
import org.parchmentmc.compass.tasks.VersionDownload
import org.parchmentmc.tasks.*
import org.parchmentmc.util.ArtifactVersionProvider
import org.parchmentmc.util.replace
import org.parchmentmc.validation.MemberExistenceValidatorV2
import org.parchmentmc.validation.MethodStandardsValidatorV2

plugins {
    java
    `maven-publish`
    id("org.parchmentmc.compass")
}

//apply<org.parchmentmc.BlackstonePlugin>()

val mcVersion = providers.gradleProperty("mcVersion")
compass {
    version = mcVersion
}

repositories {
    maven("https://libraries.minecraft.net/") {
        name = "Minecraft"
    }
    mavenCentral() // for Enigma's dependency flatlaf
    maven("https://maven.neoforged.net/") {
        name = "NeoForged"
    }
    maven("https://maven.fabricmc.net/") {
        name = "FabricMC"
    }
}

val enigma by configurations.registering
val remapper by configurations.registering
val minecraft by configurations.registering {
    isTransitive = false
}

configurations.jammer {
    val asmVersion = "9.9"
    resolutionStrategy.force(
        "org.ow2.asm:asm-tree:$asmVersion", // bump ParchmentJam's ASM for Java 21+ required since MC 1.20.5
        // update JarAwareMapping versions too to match the new version
        "org.ow2.asm:asm:$asmVersion",
        "org.ow2.asm:asm-util:$asmVersion",
        "org.ow2.asm:asm-commons:$asmVersion"
    )
}

dependencies {
    // MCPConfig for the SRG intermediate
    mcpconfig("de.oceanlabs.mcp:mcp_config:1.16.5-20210303.130956")

    // ART for remapping the client JAR
    remapper("net.neoforged:AutoRenamingTool:2.0.17")

    // Enigma, pretty interface for editing mappings
    enigma("cuchaz:enigma-swing:4.0.2")
    enigma("org.vineflower:vineflower:1.11.1") // sync with mache
    enigma(project(":enigma-plugin", "runtimeElements"))

    // ParchmentJAM, JAMMER integration for migrating mapping data
    jammer("org.parchmentmc.jam:jam-parchment:0.1.0")

    // Minecraft classpath for inheritance check in ART and to prevent types coming from libraries to be printed as FQN in enigma
    val manifest = project.plugins.getPlugin(CompassPlugin::class).manifestsDownloader.versionManifest
    for (library in manifest.get().libraries) {
        minecraft(library.name)
    }
}

val downloadClientJar by tasks.registering(VersionDownload::class) {
    group = CompassPlugin.COMPASS_GROUP
    description = "Downloads the client JAR for the current version set in Compass."
    outputs.cacheIf { true }
}

val remapJar by tasks.registering(RemapJar::class) {
    group = "parchment"
    description = "Remaps the client JAR with the Mojang obfuscation mappings."

    val obfDL = project.plugins.getPlugin(CompassPlugin::class).obfuscationMapsDownloader
    inputJar = downloadClientJar.flatMap { it.outputFile }
    mappings = obfDL.obfuscationMap.flatMap {  _ -> obfDL.clientDownloadOutput }

    remapperClasspath.setFrom(remapper)
    minecraftClasspath.setFrom(minecraft)

    outputJar = project.layout.buildDirectory.dir("remapped")
        .zip(mcVersion) { d, ver -> d.file("$ver-client.jar") }
}

tasks.register<ScanConstructorParameters>("scanInitParams") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    inputJar = remapJar.flatMap { it.outputJar }
    inputMapping = project.compass.productionData
}

tasks.register<ScanParameter>("scanParam") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    inputMapping = project.compass.productionData
    inputJar = remapJar.flatMap { it.outputJar }
}

tasks.register<JavadocLint>("scanJavadocs") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    inputMapping = project.compass.productionData
}

tasks.register<EnigmaRunner>("enigma") {
    group = "parchment"
    description = "Runs the Enigma mapping tool"
    classpath(enigma)
    mainClass = "cuchaz.enigma.gui.Main"
    inputJar = remapJar.flatMap { it.outputJar }
    mappings = project.compass.productionData
    profile = project.layout.projectDirectory.file("enigma-plugin/profile.json")
    libraries.setFrom(minecraft)
}

val unobfuscatedClient = providers.gradleProperty("unobfuscatedClient")
if (unobfuscatedClient.isPresent) {
    val downloadUnobfuscated by tasks.registering(DownloadFile::class) {
        url = unobfuscatedClient
        output = project.layout.buildDirectory.file("$name/client.jar")
    }
    tasks.named<EnigmaRunner>("enigma").configure {
        inputs.file(downloadUnobfuscated.flatMap { it.output })
        systemProperty("minecraft.client.unobfuscatedJar", downloadUnobfuscated.flatMap { it.output }.get().asFile.absolutePath)
    }
}

tasks.withType<ValidateData>().configureEach {
    validators.replace(MemberExistenceValidator::class) { -> MemberExistenceValidatorV2() }
    validators.replace(MethodStandardsValidator::class) { -> MethodStandardsValidatorV2() }
}

tasks.withType<SanitizeData>().configureEach {
    if (project.hasProperty("compass.dryRun")) { // convenient for large renames, use this before sanitizeData allow for a clean commits tree
        sanitizers.clear()
    }
}

tasks.withType<GenerateExport>().configureEach {
    // Disable blackstone if UPDATING is set.
    // This will ensure cascaded method data does not get mixed into the production data when updating.
    useBlackstone = !project.findProperty("UPDATING")?.toString().toBoolean()
}

val artifactVersionProvider = providers.of(ArtifactVersionProvider::class) {
    parameters {
        repoUrl = "https://artifactory.papermc.io/artifactory/releases/"
        version = mcVersion
    }
}

val generateSanitizedExport by tasks.registering(GenerateSanitizedExport::class) {
    group = CompassPlugin.COMPASS_GROUP
    description = "Generates an export file using the \"official\" intermediate provider and production data"
    input = project.compass.productionData
    inputFormat = project.compass.productionDataFormat
}

val officialExportZip by tasks.registering(Zip::class) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Creates a ZIP archive containing the export produced by the \"official\" intermediate provider and production data"
    from(tasks.generateOfficialExport.flatMap { it.output })
    archiveBaseName = "officialExport"
}

val officialSanitizedExportZip by tasks.registering(Zip::class) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Creates a ZIP archive containing the sanitized export produced by the \"official\" intermediate provider and production data"
    from(generateSanitizedExport.flatMap { it.output })
    archiveBaseName = "officialSanitizedExport"
}

val officialStagingExportZip by tasks.registering(Zip::class) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Creates a ZIP archive containing the export produced by the \"official\" intermediate provider and staging data"
    from(tasks.generateOfficialStagingExport.flatMap { it.output })
    archiveBaseName = "officialStagingExport"
}

tasks.withType<Zip>().named { name -> name.startsWith("official") }.configureEach {
    rename { "parchment.json" }
    destinationDirectory = project.layout.buildDirectory.dir("exportZips")
}

publishing {
    publications.withType(MavenPublication::class).configureEach {
        pom {
            name = "Parchment Mappings"
            description = "Parameter names and javadoc mappings for Minecraft: Java Edition."
            organization {
                name = "ParchmentMC"
                url = "https://github.com/ParchmentMC"
            }
            licenses {
                license {
                    name = "CC0-1.0"
                    url = "https://creativecommons.org/publicdomain/zero/1.0/legalcode"
                }
            }
            properties.put("minecraft_version", mcVersion)
        }
        artifactId = "parchment"
    }

    publications.register<MavenPublication>("versionedExport") {
        // for remote repository (like Paper)
        artifact(officialExportZip)
        artifact(officialSanitizedExportZip) {
            classifier = "checked"
        }
        version = artifactVersionProvider.get()
    }

    publications.register<MavenPublication>("export") {
        // for mavenLocal
        artifact(officialExportZip)
        artifact(officialSanitizedExportZip) {
            classifier = "checked"
        }
        version = mcVersion.get()
    }
    publications.register<MavenPublication>("staging") {
        artifact(officialStagingExportZip)
        version = "staging-SNAPSHOT"
    }
}
