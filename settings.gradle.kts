pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    }

}


dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()   // picks up plugin-annotations
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    }
}

rootProject.name = "kotlin-function-tracer"

include("compiler-plugin")
include("gradle-plugin")
include("plugin-annotations")

