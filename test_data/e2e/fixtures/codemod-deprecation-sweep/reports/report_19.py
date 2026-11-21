"""Report 19: metrics."""
from textkit.legacy import fmt_cell

ROWS = [('sigma28', 52), ('delta58', 313), ('lambda87', 117)]


def render():
    lines = [fmt_cell("METRICS", 20, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(value=name, width=14, fill=".")
            + fmt_cell(str(qty), 6, "-")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
