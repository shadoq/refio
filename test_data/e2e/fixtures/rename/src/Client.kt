package api

class Client {
    fun load(id: Int): String = legacyFetch(id)
}
