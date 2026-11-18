"""Report 09: metrics."""
from textkit.render import render_cell

ROWS = [('alpha63', 40), ('rho67', 245), ('gamma65', 394), ('delta28', 141)]


def render():
    lines = [render_cell(12, "=", "METRICS")]
    for name, qty in ROWS:
        lines.append(render_cell(8, "-", name) + render_cell(width=4, fill=".", value=str(qty)))
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
