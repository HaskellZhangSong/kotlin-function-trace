package dev.songzh.sample

/**
 * A simple greeter. All functions are traced automatically by the plugin.
 */
class Greeter {

    fun greet(name: String): String {
        val clean = sanitize(name)
        val message = "Hello, $clean!"
        return formatMessage(message)
    }

    private fun formatMessage(message: String): String =
        message.uppercase()

    private fun sanitize(name: String): String =
        name.trim().replaceFirstChar { it.uppercase() }
}

