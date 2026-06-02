plugins {
    kotlin("jvm")
    id("com.github.gmazzo.buildconfig")
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
    compileOnly(kotlin("compiler"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(kotlin("reflect"))
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }

    packageName(group.toString())
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
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
}
