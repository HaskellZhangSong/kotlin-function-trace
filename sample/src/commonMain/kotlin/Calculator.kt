package dev.songzh.sample

fun computeFactorial2(n: Long): Long {
    if (n <= 1) return 1L
    return n * computeFactorial2(n - 1)
}

/**
 * A simple calculator that demonstrates nested traced calls.
 * All functions are traced automatically by the plugin.
 */
class Calculator {

    fun sumOfSquares(a: Int, b: Int): Int {
        val sq1 = square(a)
        val sq2 = square(b)
        return add(sq1, sq2)
    }

    fun square(n: Int): Int = n * n

    fun add(a: Int, b: Int): Int = a + b
}

