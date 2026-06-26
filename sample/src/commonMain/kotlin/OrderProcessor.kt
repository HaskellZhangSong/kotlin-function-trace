package dev.songzh.sample

/** A single line item in an order. */
data class OrderItem(val name: String, val unitPrice: Double, val quantity: Int)

/**
 * Demonstrates tracing across a realistic multi-step business workflow.
 * All functions are traced automatically by the plugin.
 */
class OrderProcessor {

    fun processOrder(items: List<OrderItem>): Double {
        validateOrder(items)
        return calculateTotal(items)
    }

    fun validateOrder(items: List<OrderItem>) {
        require(items.isNotEmpty()) { "Order must contain at least one item." }
        require(items.all { it.quantity > 0 }) { "All item quantities must be positive." }
    }

    fun calculateTotal(items: List<OrderItem>): Double {
        val subtotal = items.sumOf { it.unitPrice * it.quantity }
        return applyDiscount(subtotal, items.size)
    }

    private fun applyDiscount(subtotal: Double, itemCount: Int): Double =
        if (itemCount >= 3) subtotal * 0.95 else subtotal   // 5 % bulk discount
}
