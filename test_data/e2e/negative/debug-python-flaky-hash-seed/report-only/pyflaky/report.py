"""Human readable daily report."""
from datetime import datetime


def daily_report(scores):
    """Render the scores as a dated, ranked report."""
    day = datetime.now().strftime("%Y-%m-%d")
    lines = ["daily report %s" % day]
    for name, score in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append("%s: %d" % (name, score))
    return "\n".join(lines)
