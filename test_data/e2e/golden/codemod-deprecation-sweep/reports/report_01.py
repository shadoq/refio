"""Report 01: shipments."""
from textkit.render import render_cell

ROWS = [('delta12', 285), ('kappa21', 209), ('rho48', 289)]


def render():
    lines = [render_cell(20, "=", "SHIPMENTS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(13, " ", name)
            + render_cell(width=7, fill="_", value=str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
