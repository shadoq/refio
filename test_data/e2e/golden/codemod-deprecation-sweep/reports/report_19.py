"""Report 19: metrics."""
from textkit.render import render_cell

ROWS = [('sigma28', 52), ('delta58', 313), ('lambda87', 117)]


def render():
    lines = [render_cell(20, "=", "METRICS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(value=name, width=14, fill=".")
            + render_cell(6, "-", str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
