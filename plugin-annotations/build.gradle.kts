@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    `maven-publish`
}

kotlin {
    explicitApi()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    androidNativeX86()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    js().nodejs()

    jvm()

    linuxArm64()
    linuxX64()

    macosArm64()
    macosX64()

    mingwX64()

    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    wasmJs().nodejs()
    wasmWasi().nodejs()

    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()
    watchosX64()

    applyDefaultHierarchyTemplate()
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Function Tracer Kotlin Compiler Plugin — Annotations")
            description.set("Multiplatform annotations and default trace-hook implementations for the Function Tracer Kotlin compiler plugin.")
            url.set("https://github.com/songzhh/function-tracer-kotlin")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("songzh")
                    name.set("Song Zheng")
                    url.set("https://github.com/songzhh")
                }
            }
            scm {
                url.set("https://github.com/songzhh/function-tracer-kotlin")
                connection.set("scm:git:git://github.com/songzhh/function-tracer-kotlin.git")
                developerConnection.set("scm:git:ssh://github.com/songzhh/function-tracer-kotlin.git")
            }
        }
    }
}
