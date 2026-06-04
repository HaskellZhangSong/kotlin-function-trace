plugins {
    kotlin("jvm")
    id("com.github.gmazzo.buildconfig")
    id("java-gradle-plugin")
    `maven-publish`
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))

    testImplementation(kotlin("test-junit5"))
}

buildConfig {
    packageName(project.group.toString())

    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")

    val pluginProject = project(":compiler-plugin")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${pluginProject.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${pluginProject.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${pluginProject.version}\"")


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
