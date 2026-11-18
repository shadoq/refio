"""Report 14: sensors."""
from textkit.render import render_cell

ROWS = [('delta33', 18), ('kappa55', 347), ('zeta80', 89)]


def render():
    lines = [render_cell(width=19, fill="=", value="SENSORS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(11, "-", name)
            + render_cell(value=str(qty), width=8, fill="-")
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
