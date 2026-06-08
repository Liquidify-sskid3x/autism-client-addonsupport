plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = "autism-test-addon"
    version      = libs.versions.addon.version.get()
    group        = "com.example.testaddon"
}

repositories {
    maven { name = "meteor-maven"; url = uri("https://maven.meteordev.org/releases") }
    mavenCentral()
}

dependencies {
    "minecraft"(libs.minecraft)
    "implementation"(libs.fabric.loader)
    "implementation"(libs.fabric.api)

    // Compile against the main AUTISM Client project (runtime resolution by Fabric Loader)
    "compileOnly"(project(":"))
}

tasks {
    processResources {
        val props = mapOf(
            "version"    to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") { expand(props) }
    }

    java {
        sourceCompatibility = JavaVersion.toVersion(25)
        targetCompatibility = JavaVersion.toVersion(25)
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
