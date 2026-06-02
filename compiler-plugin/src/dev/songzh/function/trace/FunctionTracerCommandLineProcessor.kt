package dev.songzh.function.trace

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

@Suppress("unused") // Used via reflection.
class FunctionTracerCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption(
            optionName = "traceAll",
            valueDescription = "<true|false>",
            description = "When true, every non-inline function is traced; " +
                    "when false (default) only functions annotated with @Trace are traced.",
            required = false,
        ),
        CliOption(
            optionName = "packagePath",
            valueDescription = "<fully.qualified.package>",
            description = "Package that contains _funcTraceEnter / _funcTraceExit. " +
                    "Defaults to 'dev.songzh.function.trace'.",
            required = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "traceAll" -> configuration.put(FunctionTracerConfigurationKeys.TRACE_ALL, value.toBoolean())
            "packagePath" -> configuration.put(FunctionTracerConfigurationKeys.PACKAGE_PATH, value)
            else -> error("Unexpected config option: '${option.optionName}'")
        }
    }
}
