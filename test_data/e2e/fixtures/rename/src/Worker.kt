package api

class Worker {
    fun process(ids: List<Int>): List<String> = ids.map { legacyFetch(it) }
}
