"""Report 03: tickets."""
from textkit.render import render_cell

ROWS = [('lambda47', 114), ('rho60', 229), ('lambda59', 207), ('alpha89', 172), ('beta61', 258), ('theta65', 45)]


def render():
    lines = [render_cell(16, "=", "TICKETS")]
    for name, qty in ROWS:
        lines.append(render_cell(width=10, fill="_", value=name) + render_cell(6, "_", str(qty)))
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
