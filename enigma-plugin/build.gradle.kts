plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") {
        name = "FabricMC"
    }
}

dependencies {
    implementation("cuchaz:enigma:4.0.2")
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
}
