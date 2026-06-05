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
