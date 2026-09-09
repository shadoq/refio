package validate

/** Returns true when [age] is a valid adult age (18..120 inclusive). */
fun isAdult(age: Int): Boolean {
    return age >= 18 && age <= 120
}
