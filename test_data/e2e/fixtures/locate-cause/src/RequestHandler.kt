package app

/** Uses the seconds value to wait - so it waits 30000 seconds, not 30. Symptom shows here. */
class RequestHandler(private val service: TimeoutService) {
    fun deadlineSeconds(): Int = service.timeoutSeconds()
}
