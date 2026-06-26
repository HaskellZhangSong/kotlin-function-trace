plugins {
    kotlin("jvm")
    application
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("src/commonMain/kotlin"))
    }
}

dependencies {
    // No external dependencies needed — trace hooks are defined in this project.
}

application {
    mainClass.set("dev.songzh.sample.MainKt")
}

// ---------------------------------------------------------------------------
// Wire the compiler plugin from the sibling :compiler-plugin subproject.
//
// In a real consumer project you would instead apply the Gradle plugin:
//
//   plugins {
//       id("dev.songzh.function.trace") version "0.2.0"
//   }
//   functionTracer {
//       traceAll = true          // true by default — all non-inline functions are traced
//       packagePath = "dev.songzh.function.trace"
//   }
//
// Here we wire it manually so the sample works without publishing to Maven.
// ---------------------------------------------------------------------------
val compilerPluginJar = project(":compiler-plugin").tasks.named<Jar>("jar")

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // Ensure the plugin JAR is built before compilation.
    dependsOn(compilerPluginJar)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // Load the plugin JAR.
        freeCompilerArgs.add(
            compilerPluginJar
                .flatMap { it.archiveFile }
                .map { "-Xplugin=${it.asFile.absolutePath}" }
        )
        // Plugin options — traceAll defaults to true (all non-inline functions are traced).
        freeCompilerArgs.add("-P")
        freeCompilerArgs.add("plugin:dev.songzh.function.trace:traceAll=true")
        freeCompilerArgs.add("-P")
        freeCompilerArgs.add("plugin:dev.songzh.function.trace:packagePath=dev.songzh.function.trace")
    }
}

