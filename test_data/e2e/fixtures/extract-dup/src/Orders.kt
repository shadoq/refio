package orders

class Orders {
    fun place(qty: Int, price: Int): String {
        if (qty <= 0) throw IllegalArgumentException("qty must be > 0")
        if (price < 0) throw IllegalArgumentException("price must be >= 0")
        return "placed $qty @ $price"
    }

    fun update(qty: Int, price: Int): String {
        if (qty <= 0) throw IllegalArgumentException("qty must be > 0")
        if (price < 0) throw IllegalArgumentException("price must be >= 0")
        return "updated $qty @ $price"
    }
}
