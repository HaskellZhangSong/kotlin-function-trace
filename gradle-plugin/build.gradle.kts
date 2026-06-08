import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm")
    id("java-gradle-plugin")
    `maven-publish`
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        java.srcDir(layout.buildDirectory.dir("generated/buildconfig/src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig/src")
    val pluginId = rootProject.group.toString()
    val pluginGroup = project(":compiler-plugin").group.toString()
    val pluginName = project(":compiler-plugin").name
    val pluginVersion = project(":compiler-plugin").version.toString()
    inputs.property("pluginId", pluginId)
    inputs.property("pluginGroup", pluginGroup)
    inputs.property("pluginName", pluginName)
    inputs.property("pluginVersion", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("dev/songzh/function/trace")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            package dev.songzh.function.trace

            internal object BuildConfig {
                const val KOTLIN_PLUGIN_ID: String = "$pluginId"
                const val KOTLIN_PLUGIN_GROUP: String = "$pluginGroup"
                const val KOTLIN_PLUGIN_NAME: String = "$pluginName"
                const val KOTLIN_PLUGIN_VERSION: String = "$pluginVersion"
            }
            """.trimIndent()
        )
    }
}

tasks.named("compileKotlin") { dependsOn(generateBuildConfig) }

// ---------------------------------------------------------------------------
// Gradle 7.x compatibility: strip the Kotlin 2.x .kotlin_module header
//
// Kotlin 2.x prepends a 20-byte binary version header to every .kotlin_module
// file before the protobuf payload.  Gradle 7.6.3 bundles Kotlin 1.7.10 as
// its Kotlin DSL compiler, which reads plugin JARs on the build-script
// classpath and tries to parse .kotlin_module directly as raw protobuf.
// Because the first byte of the new header is 0x00 (protobuf field number 0
// is invalid), the parse fails with:
//   "Protocol message contained an invalid tag (zero)."
//
// Fix: in a doLast action on the jar task, rewrite the JAR and strip the
// 20-byte header from any embedded .kotlin_module files so the file starts
// with a valid protobuf tag — exactly what Kotlin 1.7.x expects.
// The header is detected by its magic: first 4 bytes == 0x00 0x00 0x00 0x03.
// ---------------------------------------------------------------------------

/** Strips the 20-byte Kotlin 2.x version header if present, returning the raw protobuf payload. */
fun stripKotlin2xHeader(bytes: ByteArray): ByteArray =
    if (bytes.size >= 20 &&
        bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x03.toByte()
    ) bytes.copyOfRange(20, bytes.size) else bytes

tasks.named<Jar>("jar") {
    doLast {
        val jarFile = archiveFile.get().asFile
        val tmpFile = temporaryDir.resolve("patched-for-gradle7.jar")

        ZipFile(jarFile).use { zip ->
            ZipOutputStream(BufferedOutputStream(tmpFile.outputStream())).use { out ->
                for (entry in zip.entries().asSequence()) {
                    val original = zip.getInputStream(entry).readBytes()
                    val patched = if (entry.name.endsWith(".kotlin_module")) {
                        stripKotlin2xHeader(original).also { stripped ->
                            if (stripped !== original)
                                logger.lifecycle("Patched ${entry.name}: stripped 20-byte Kotlin 2.x header for Gradle 7.x compatibility")
                        }
                    } else original

                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(patched)
                    out.closeEntry()
                }
            }
        }

        jarFile.delete()
        tmpFile.renameTo(jarFile)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))

    testImplementation(kotlin("test-junit5"))
}

gradlePlugin {
    plugins {
        create("FunctionTracerPlugin") {
            id = rootProject.group.toString()
            displayName = "Function Tracer Kotlin Compiler Plugin"
            description = "Kotlin compiler plugin that automatically injects entry/exit trace calls into function bodies at compile time"
            implementationClass = "dev.songzh.function.trace.FunctionTracerGradlePlugin"
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(sourcesJar)
        pom {
            name.set("Function Tracer Kotlin Compiler Plugin — Gradle Plugin")
            description.set("Gradle plugin that wires the Function Tracer Kotlin compiler plugin into any Kotlin build.")
            url.set("https://github.com/HaskellZhangSong/kotlin-function-trace/")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("songzh")
                    name.set("Song Zhang")
                    url.set("https://github.com/HaskellZhangSong")
                }
            }
            scm {
                url.set("https://github.com/HaskellZhangSong/kotlin-function-trace/")
                connection.set("scm:git:git@github.com:HaskellZhangSong/kotlin-function-trace.git")
                developerConnection.set("scm:git:ssh://github.com/HaskellZhangSong/kotlin-function-trace.git")
            }
        }
    }
}
