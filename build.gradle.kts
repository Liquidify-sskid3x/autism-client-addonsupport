import java.io.OutputStream

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.xmlgraphics:batik-transcoder:1.17")
        classpath("org.apache.xmlgraphics:batik-codec:1.17")
    }
}

plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    mavenCentral()
}

tasks.register("listConfigs") {
    doLast {
        configurations.forEach { println(it.name) }
    }
}

dependencies {

    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)

    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("net.java.dev.jna:jna-platform:5.13.0")
    implementation("io.netty:netty-handler-proxy:4.1.118.Final")
    implementation("io.netty:netty-codec-socks:4.1.118.Final")
    include("io.netty:netty-handler-proxy:4.1.118.Final")
    include("io.netty:netty-codec-socks:4.1.118.Final")
    implementation("de.florianreuth:waybackauthlib:1.1.0")
    include("de.florianreuth:waybackauthlib:1.1.0")
    implementation("com.github.weisj:jsvg:2.1.0")
    include("com.github.weisj:jsvg:2.1.0")

    // Bundle a newer MixinExtras (jar-in-jar) so Fabric Loader loads it over the old 0.5.0 it ships
    // with. Required because ViaFabricPlus (and our own WrapOperation/ModifyExpressionValue mixins)
    // need MixinExtras >= 0.5.3; without this, joining a server crashes during mixin application.
    implementation("io.github.llamalad7:mixinextras-fabric:0.5.4")
    include("io.github.llamalad7:mixinextras-fabric:0.5.4")

}

val generatedAutismResourcesDir = layout.buildDirectory.dir("generated/resources/autism/main")

sourceSets {
    main {
        resources.srcDir(generatedAutismResourcesDir)
    }
}

val generateVanillaUiAssets by tasks.registering {
    // Semantic feature icons used by the vanilla-friendly UI. Structural
    // actions such as close and reorder are rendered as text symbols.
    val iconSourceDir = file("assets/icons")
    val outputDir = generatedAutismResourcesDir.map { it.dir("assets/autismclient") }

    inputs.dir(iconSourceDir)
    outputs.dir(outputDir)

    doLast {
        val targetRoot = outputDir.get().asFile
        targetRoot.parentFile.resolve("yu" + "ng" + "light").deleteRecursively()
        targetRoot.deleteRecursively()
        val iconTargetDir = targetRoot.resolve("textures/gui/vanillaui/icons")

        iconTargetDir.mkdirs()

        val pngTranscoderClass = Class.forName("org.apache.batik.transcoder.image.PNGTranscoder")
        val transcoderInputClass = Class.forName("org.apache.batik.transcoder.TranscoderInput")
        val transcoderOutputClass = Class.forName("org.apache.batik.transcoder.TranscoderOutput")
        val transcodingHintsKeyClass = Class.forName("org.apache.batik.transcoder.TranscodingHints\$Key")

        val transcoder = pngTranscoderClass.getDeclaredConstructor().newInstance()
        val widthField = pngTranscoderClass.getField("KEY_WIDTH")
        val heightField = pngTranscoderClass.getField("KEY_HEIGHT")
        val addHint = pngTranscoderClass.getMethod("addTranscodingHint", transcodingHintsKeyClass, Any::class.java)
        addHint.invoke(transcoder, widthField.get(null), 256f)
        addHint.invoke(transcoder, heightField.get(null), 256f)

        val inputCtor = transcoderInputClass.getConstructor(String::class.java)
        val outputCtor = transcoderOutputClass.getConstructor(OutputStream::class.java)
        val transcode = pngTranscoderClass.getMethod("transcode", transcoderInputClass, transcoderOutputClass)

        iconSourceDir.listFiles { file -> file.isFile && file.extension.equals("svg", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { svg ->
                val outputFile = iconTargetDir.resolve(svg.nameWithoutExtension.lowercase() + ".png")
                outputFile.outputStream().use { out ->
                    val input = inputCtor.newInstance(svg.toURI().toString())
                    val output = outputCtor.newInstance(out)
                    transcode.invoke(transcoder, input, output)
                }
            }
    }
}

val generateAutismInspectorMappings by tasks.registering {
    val mappingFiles = fileTree(".gradle/loom-cache/source_mappings") {
        include("**/*.tiny")
    }
    val outputFile = generatedAutismResourcesDir.map { it.file("autism-inspector-mappings.tsv") }

    inputs.files(mappingFiles)
    outputs.file(outputFile)

    doLast {
        run {
            val target = outputFile.get().asFile
            target.parentFile.mkdirs()
            target.writeText(
                "# Official Mojang mappings are used for Minecraft 26.1.2; no Yarn aliases are generated.\n",
                Charsets.UTF_8
            )
            return@doLast
        }

        val tinyFile = mappingFiles.files
            .filter { it.isFile }
            .maxByOrNull { it.lastModified() }
            ?: error("Missing Loom source mappings under ${project.file(".gradle/loom-cache/source_mappings").absolutePath}")

        val lines = tinyFile.readLines(Charsets.UTF_8)
        val header = lines.firstOrNull { it.startsWith("tiny\t") }
            ?: error("Invalid tiny mapping file: ${tinyFile.absolutePath}")
        val namespaces = header.split('\t').drop(3)
        val namedIndex = namespaces.indexOf("named")
        val intermediaryIndex = namespaces.indexOf("intermediary")
        require(namedIndex >= 0 && intermediaryIndex >= 0) {
            "Tiny mapping header must expose named and intermediary namespaces: $header"
        }

        fun readNamespace(parts: List<String>, baseOffset: Int, namespaceIndex: Int): String {
            val index = baseOffset + namespaceIndex
            return if (index in parts.indices) parts[index].trim() else ""
        }

        val namedToIntermediary = linkedMapOf<String, String>()
        val classAliases = linkedMapOf<String, String>()

        for (raw in lines) {
            val trimmed = raw.trimStart('\t')
            if (!trimmed.startsWith("c\t")) continue
            val parts = trimmed.split('\t')
            val namedName = readNamespace(parts, 1, namedIndex)
            val intermediaryName = readNamespace(parts, 1, intermediaryIndex)
            if (namedName.isBlank() || intermediaryName.isBlank()) continue
            namedToIntermediary[namedName] = intermediaryName
            classAliases[intermediaryName.replace('/', '.')] = namedName.substringAfterLast('/').replace('$', '.')
        }

        fun remapDescriptorToIntermediary(descriptor: String): String {
            if (descriptor.isBlank()) return descriptor
            val classRef = Regex("L([^;]+);")
            return classRef.replace(descriptor) { match ->
                val namedInternalName = match.groupValues[1]
                val intermediaryInternalName = namedToIntermediary[namedInternalName] ?: namedInternalName
                "L$intermediaryInternalName;"
            }
        }

        fun storeAlias(
            target: MutableMap<String, String>,
            ambiguous: MutableSet<String>,
            key: String,
            alias: String
        ) {
            if (key.isBlank() || alias.isBlank() || ambiguous.contains(key)) return
            val existing = target[key]
            if (existing == null) {
                target[key] = alias
                return
            }
            if (existing != alias) {
                target.remove(key)
                ambiguous.add(key)
            }
        }

        val fieldAliases = linkedMapOf<String, String>()
        val methodAliases = linkedMapOf<String, String>()
        val ambiguousFieldKeys = linkedSetOf<String>()
        val ambiguousMethodKeys = linkedSetOf<String>()

        var currentOwner = ""
        for (raw in lines) {
            val trimmed = raw.trimStart('\t')
            if (trimmed.isBlank()) continue
            val parts = trimmed.split('\t')
            when (parts.firstOrNull()) {
                "c" -> {
                    currentOwner = readNamespace(parts, 1, intermediaryIndex).replace('/', '.')
                }

                "f" -> {
                    if (currentOwner.isBlank()) continue
                    val descriptor = if (parts.size > 1) remapDescriptorToIntermediary(parts[1]) else ""
                    val namedName = readNamespace(parts, 2, namedIndex)
                    val intermediaryName = readNamespace(parts, 2, intermediaryIndex)
                    if (namedName.isBlank() || intermediaryName.isBlank()) continue
                    storeAlias(fieldAliases, ambiguousFieldKeys, "$currentOwner#$intermediaryName#$descriptor", namedName)
                }

                "m" -> {
                    if (currentOwner.isBlank()) continue
                    val descriptor = if (parts.size > 1) remapDescriptorToIntermediary(parts[1]) else ""
                    val namedName = readNamespace(parts, 2, namedIndex)
                    val intermediaryName = readNamespace(parts, 2, intermediaryIndex)
                    if (namedName.isBlank() || intermediaryName.isBlank()) continue
                    if (namedName == "<init>" || namedName == "<clinit>") continue
                    storeAlias(methodAliases, ambiguousMethodKeys, "$currentOwner#$intermediaryName#$descriptor", namedName)
                }
            }
        }

        val authAliasFragments = listOf(
            "session", "accesstoken", "refreshtoken", "authtoken",
            "clientsession", "playersession", "publicsession"
        )
        fun isAuthAlias(alias: String): Boolean {
            val normalized = alias.lowercase().replace(Regex("[^a-z0-9]"), "")
            return authAliasFragments.any { normalized.contains(it) }
        }

        val filteredClassAliases = classAliases.filter { (_, alias) -> !isAuthAlias(alias) }
        val blockedOwners = classAliases.keys.filter { owner ->
            val alias = classAliases[owner] ?: return@filter false
            isAuthAlias(alias)
        }.toSet()

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.printWriter(Charsets.UTF_8).use { out ->
            out.println("# Yarn deobfuscation mappings")
            filteredClassAliases.toSortedMap().forEach { (owner, alias) ->
                out.println("C\t$owner\t$alias")
            }
            fieldAliases.toSortedMap().forEach { (key, alias) ->
                val parts = key.split('#', limit = 3)
                if (parts.size == 3 && !blockedOwners.contains(parts[0]) && !isAuthAlias(alias)) {
                    out.println("F\t${parts[0]}\t${parts[1]}\t${parts[2]}\t$alias")
                }
            }
            methodAliases.toSortedMap().forEach { (key, alias) ->
                val parts = key.split('#', limit = 3)
                if (parts.size == 3 && !blockedOwners.contains(parts[0]) && !isAuthAlias(alias)) {
                    out.println("M\t${parts[0]}\t${parts[1]}\t${parts[2]}\t$alias")
                }
            }
        }
    }
}

tasks {
    processResources {
        dependsOn(generateAutismInspectorMappings)
        dependsOn(generateVanillaUiAssets)
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        exclude("addon-template.mixins.json")
        exclude("assets/template/**")

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        // ModMenu API is a compile-only soft dependency: we ship local stubs of its two API
        // interfaces so we can compile the integration without a cross-version ModMenu artifact, but
        // we must NOT bundle them — at runtime the real ModMenu provides them (and if ModMenu is
        // absent, our integration entrypoint is simply never loaded).
        exclude("com/terraformersmc/**")
    }

    java {
        sourceCompatibility = JavaVersion.toVersion(25)
        targetCompatibility = JavaVersion.toVersion(25)
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.add("-Xlint:-restricted")
    }
}
