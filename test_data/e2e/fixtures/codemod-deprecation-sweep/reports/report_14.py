"""Report 14: sensors."""
from textkit.legacy import fmt_cell

ROWS = [('delta33', 18), ('kappa55', 347), ('zeta80', 89)]


def render():
    lines = [fmt_cell("SENSORS", width=19, fill="=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 11, "-")
            + fmt_cell(value=str(qty), width=8, fill="-")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
