"""Report 16: budget."""
from textkit.render import render_cell

ROWS = [('rho50', 280), ('sigma94', 287), ('gamma83', 338), ('iota9', 179), ('theta5', 240)]


def render():
    lines = [render_cell(width=19, fill="=", value="BUDGET")]
    for name, qty in ROWS:
        lines.append(
            render_cell(width=12, fill=".", value=name)
            + render_cell(value=str(qty), width=7, fill="_")
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
