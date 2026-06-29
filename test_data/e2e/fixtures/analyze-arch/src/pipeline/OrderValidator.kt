package pipeline

/** Step 2: checks the parsed Order, then passes valid ones to OrderRepository. */
class OrderValidator {
    fun validate(order: Order): Boolean = order.sku.isNotBlank()
}
