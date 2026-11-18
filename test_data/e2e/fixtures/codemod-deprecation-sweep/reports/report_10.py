"""Report 10: inventory."""
from textkit.legacy import fmt_cell

ROWS = [('lambda11', 211), ('kappa53', 85), ('gamma56', 448), ('rho27', 55)]


def render():
    lines = [fmt_cell("INVENTORY", width=22, fill="=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 14, ".")
            + fmt_cell(str(qty), 8, "_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
