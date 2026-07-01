package pipeline

/** Step 4: the terminal sink - emits a confirmation for a persisted Order. */
class OrderNotifier {
    fun notify(order: Order): String = "stored ${order.sku}"
}
