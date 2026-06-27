package app

class Logger(private val tag: String) {
    fun info(msg: String) = println("[$tag] $msg")
}
