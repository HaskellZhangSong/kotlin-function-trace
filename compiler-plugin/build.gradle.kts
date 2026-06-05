plugins {
    kotlin("jvm")
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
    inputs.property("pluginId", pluginId)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("dev/songzh/function/trace")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            package dev.songzh.function.trace

            internal object BuildConfig {
                const val KOTLIN_PLUGIN_ID: String = "$pluginId"
            }
            """.trimIndent()
        )
    }
}

tasks.named("compileKotlin") { dependsOn(generateBuildConfig) }

dependencies {
    compileOnly(kotlin("compiler"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(kotlin("reflect"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar)
            artifactId = "compiler-plugin"
            pom {
                name.set("Function Tracer Kotlin Compiler Plugin — Compiler Plugin")
                description.set("K2 compiler plugin that injects entry/exit trace calls into Kotlin function bodies at compile time.")
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
}
