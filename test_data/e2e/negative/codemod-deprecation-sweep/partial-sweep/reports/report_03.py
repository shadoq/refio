"""Report 03: tickets."""
from textkit.legacy import fmt_cell

ROWS = [('lambda47', 114), ('rho60', 229), ('lambda59', 207), ('alpha89', 172), ('beta61', 258), ('theta65', 45)]


def render():
    lines = [fmt_cell("TICKETS", 16, "=")]
    for name, qty in ROWS:
        lines.append(fmt_cell(name, width=10, fill="_") + fmt_cell(str(qty), 6, "_"))
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
