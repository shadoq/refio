"""Report 04: sensors."""
from textkit.legacy import fmt_cell

ROWS = [('beta93', 314), ('lambda89', 343), ('lambda82', 97), ('delta44', 334), ('alpha90', 394), ('rho20', 433)]


def render():
    lines = [fmt_cell("SENSORS", 18, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, width=12, fill=".")
            + fmt_cell(str(qty), 6)
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
