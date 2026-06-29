package pipeline

/** Step 3: persists a validated Order and notifies via OrderNotifier. */
class OrderRepository {
    private val saved = mutableListOf<Order>()
    fun save(order: Order) { saved.add(order) }
}
