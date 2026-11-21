package pl.jclab.refio.core.services.longtask

/** Whether a round moved the task forward, which decides when a stuck loop gives up. */
object RoundProgress {
    /** A file written, more plan steps done than before, or fewer error lines in the check than before. */
    fun madeProgress(previous: RoundRecord?, current: RoundRecord): Boolean {
        if (current.filesWritten > 0) {
            return true
        }
        if (current.planStepsDone > (previous?.planStepsDone ?: 0)) {
            return true
        }
        val errorsBefore = previous?.errorLines ?: return false
        val errorsNow = current.errorLines ?: return false
        return errorsNow < errorsBefore
    }
}
