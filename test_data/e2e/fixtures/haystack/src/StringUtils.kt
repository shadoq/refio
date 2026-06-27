package app

object StringUtils {
    fun slug(s: String): String = s.lowercase().replace(" ", "-")
}
