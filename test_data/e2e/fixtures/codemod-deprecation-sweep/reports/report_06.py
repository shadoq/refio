"""Report 06: budget."""
from textkit.legacy import fmt_cell

ROWS = [('alpha30', 228), ('alpha27', 477), ('zeta42', 338), ('alpha99', 108), ('lambda98', 159)]


def render():
    lines = [fmt_cell("BUDGET", 18, "=")]
    for name, qty in ROWS:
        lines.append(fmt_cell(name, 13, "*") + fmt_cell(value=str(qty), width=5, fill="_"))
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
