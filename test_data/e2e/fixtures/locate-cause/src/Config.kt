package app

/** Central config. The request timeout is the ROOT CAUSE: milliseconds, not seconds. */
object Config {
    // BUG ORIGIN: meant to be 30 seconds, but this value is treated as seconds downstream.
    const val requestTimeout = 30000
}
