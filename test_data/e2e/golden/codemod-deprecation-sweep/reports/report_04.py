"""Report 04: sensors."""
from textkit.render import render_cell

ROWS = [('beta93', 314), ('lambda89', 343), ('lambda82', 97), ('delta44', 334), ('alpha90', 394), ('rho20', 433)]


def render():
    lines = [render_cell(18, "=", "SENSORS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(width=12, fill=".", value=name)
            + render_cell(6, " ", str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
