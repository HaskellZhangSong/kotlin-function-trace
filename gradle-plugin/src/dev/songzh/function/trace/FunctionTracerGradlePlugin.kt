package dev.songzh.function.trace

import dev.songzh.function.trace.BuildConfig.ANNOTATIONS_LIBRARY_COORDINATES
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

@Suppress("unused") // Used via reflection.
class FunctionTracerGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // Expose the DSL block as `functionTracer { … }` in user build scripts.
        target.extensions.create("functionTracer", FunctionTracerGradleExtension::class.java)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        // Add the annotations + runtime library to the compilation's compile classpath.
        kotlinCompilation.dependencies { implementation(ANNOTATIONS_LIBRARY_COORDINATES) }
        if (kotlinCompilation.implementationConfigurationName == "metadataCompilationImplementation") {
            project.dependencies.add("commonMainImplementation", ANNOTATIONS_LIBRARY_COORDINATES)
        }

        return project.provider {
            val extension = project.extensions.getByType(FunctionTracerGradleExtension::class.java)
            listOf(
                SubpluginOption(key = "traceAll", value = extension.traceAll.get().toString()),
                SubpluginOption(key = "packagePath", value = extension.packagePath.get()),
            )
        }
    }
}
