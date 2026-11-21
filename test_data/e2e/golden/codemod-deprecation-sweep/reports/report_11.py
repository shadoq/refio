"""Report 11: shipments."""
from textkit.render import render_cell

ROWS = [('omega4', 213), ('gamma85', 227), ('kappa88', 227), ('lambda34', 92), ('beta60', 340)]


def render():
    lines = [render_cell(17, "=", "SHIPMENTS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(10, " ", name)
            + render_cell(7, "_", str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
