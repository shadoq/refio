"""Report 06: budget."""
from textkit.render import render_cell

ROWS = [('alpha30', 228), ('alpha27', 477), ('zeta42', 338), ('alpha99', 108), ('lambda98', 159)]


def render():
    lines = [render_cell(18, "=", "BUDGET")]
    for name, qty in ROWS:
        lines.append(render_cell(13, "*", name) + render_cell(value=str(qty), width=5, fill="_"))
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
