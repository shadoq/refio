"""Time helpers."""
import scheduler_runtime as _scheduler


def sleep_ticks(n):
    """Suspend the caller for n ticks. The tick length is fixed by the scheduler runtime."""
    _scheduler.wait(ticks=n)
