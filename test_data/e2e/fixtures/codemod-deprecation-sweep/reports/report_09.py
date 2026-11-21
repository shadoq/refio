"""Report 09: metrics."""
from textkit.legacy import fmt_cell

ROWS = [('alpha63', 40), ('rho67', 245), ('gamma65', 394), ('delta28', 141)]


def render():
    lines = [fmt_cell("METRICS", 12, "=")]
    for name, qty in ROWS:
        lines.append(fmt_cell(name, 8, "-") + fmt_cell(str(qty), width=4, fill="."))
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
