"""Report 05: roster."""
from textkit.render import render_cell

ROWS = [('kappa69', 211), ('zeta82', 154), ('sigma71', 339), ('gamma63', 63), ('zeta42', 189), ('delta5', 339)]


def render():
    lines = [render_cell(21, "=", "ROSTER")]
    for name, qty in ROWS:
        lines.append(
            render_cell(13, "-", name)
            + render_cell(8, " ", str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
