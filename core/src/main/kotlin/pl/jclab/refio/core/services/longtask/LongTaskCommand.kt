package pl.jclab.refio.core.services.longtask

/** The `/until` and `/long` commands, parsed the same way for the TUI and the IDE. */
sealed interface LongTaskCommand {
    data class Until(val command: String) : LongTaskCommand
    object UntilOff : LongTaskCommand
    object On : LongTaskCommand
    object Off : LongTaskCommand
    object Status : LongTaskCommand
    data class Rounds(val rounds: Int) : LongTaskCommand
    data class Hours(val hours: Double) : LongTaskCommand
    data class Invalid(val message: String) : LongTaskCommand

    companion object {
        const val MAX_ROUNDS = 1_000
        const val MAX_HOURS = 168.0

        private const val USAGE = "Usage: /long on | /long off | /long rounds <1-$MAX_ROUNDS> | /long hours <up to 168>"
        private val WHITESPACE = Regex("\\s+")

        /** Null when [input] is not a `/until` or `/long` command, so it goes to the agent as a message. */
        fun parse(input: String): LongTaskCommand? {
            val parts = input.trim().split(WHITESPACE, limit = 2)
            val rest = parts.getOrNull(1)?.trim().orEmpty()
            return when (parts[0]) {
                "/until" -> parseUntil(rest)
                "/long" -> parseLong(rest)
                else -> null
            }
        }

        private fun parseUntil(rest: String): LongTaskCommand = when {
            rest.isEmpty() -> Status
            rest.equals("off", ignoreCase = true) -> UntilOff
            else -> Until(rest)
        }

        private fun parseLong(rest: String): LongTaskCommand {
            val words = rest.split(WHITESPACE).filter { it.isNotEmpty() }
            val argument = words.getOrNull(1)?.takeIf { words.size == 2 }
            return when (words.firstOrNull()?.lowercase()) {
                null -> Status
                "on" -> if (words.size == 1) On else Invalid(USAGE)
                "off" -> if (words.size == 1) Off else Invalid(USAGE)
                "rounds" -> argument?.toIntOrNull()?.takeIf { it in 1..MAX_ROUNDS }?.let { Rounds(it) }
                    ?: Invalid("Rounds must be a whole number from 1 to $MAX_ROUNDS, for example /long rounds 20.")
                "hours" -> argument?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_HOURS }?.let { Hours(it) }
                    ?: Invalid("Hours must be a number above 0 and at most 168, for example /long hours 1.5.")
                else -> Invalid(USAGE)
            }
        }
    }
}
