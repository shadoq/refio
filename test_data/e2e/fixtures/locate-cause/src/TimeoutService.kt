package app

/** Passes Config.requestTimeout straight through as a seconds value (no conversion). */
class TimeoutService {
    fun timeoutSeconds(): Int = Config.requestTimeout
}
