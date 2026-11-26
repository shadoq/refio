"""Human readable daily report."""
from datetime import datetime


def daily_report(scores, now=None):
    """Render the scores as a dated, ranked report.

    The clock is injectable: pass now=<datetime> for deterministic output
    (tests and batch jobs should always inject it).
    """
    moment = now if now is not None else datetime.now()
    day = moment.strftime("%Y-%m-%d")
    lines = ["daily report %s" % day]
    for name, score in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append("%s: %d" % (name, score))
    return "\n".join(lines)
