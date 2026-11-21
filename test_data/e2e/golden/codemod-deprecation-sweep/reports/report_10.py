"""Report 10: inventory."""
from textkit.render import render_cell

ROWS = [('lambda11', 211), ('kappa53', 85), ('gamma56', 448), ('rho27', 55)]


def render():
    lines = [render_cell(width=22, fill="=", value="INVENTORY")]
    for name, qty in ROWS:
        lines.append(
            render_cell(14, ".", name)
            + render_cell(8, "_", str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
