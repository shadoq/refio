package validate

/** Returns true when [age] is a valid adult age (18..120 inclusive). */
fun isAdult(age: Int): Boolean {
    // BUG: off-by-one - excludes exactly 18, so an 18-year-old is rejected.
    return age > 18 && age <= 120
}
