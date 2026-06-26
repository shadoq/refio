package app

class Repository {
    private val users = mutableMapOf<Int, User>()
    fun put(u: User) { users[u.id] = u }
    fun get(id: Int): User? = users[id]
}
