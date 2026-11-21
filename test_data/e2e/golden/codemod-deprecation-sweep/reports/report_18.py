"""Report 18: orders."""
from textkit.render import render_cell

ROWS = [('iota99', 203), ('rho13', 347), ('delta76', 163), ('delta95', 413), ('gamma68', 370), ('rho53', 111)]


def render():
    lines = [render_cell(18, "=", "ORDERS")]
    for name, qty in ROWS:
        lines.append(render_cell(width=14, fill="_", value=name) + render_cell(4, "-", str(qty)))
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
