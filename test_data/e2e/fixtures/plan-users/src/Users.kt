package users

data class User(val email: String, val age: Int)

object UserService {
    private val users = mutableListOf<User>()

    fun createUser(email: String, age: Int): User {
        val u = User(email, age)
        users.add(u)
        return u
    }
}
