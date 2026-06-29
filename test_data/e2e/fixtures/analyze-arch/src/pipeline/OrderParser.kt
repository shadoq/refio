package pipeline

/** Step 1: turns raw text into an Order. Hands its result to OrderValidator. */
class OrderParser {
    fun parse(raw: String): Order = Order(raw.trim())
}

data class Order(val sku: String)
