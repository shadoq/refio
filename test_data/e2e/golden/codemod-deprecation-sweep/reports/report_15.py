"""Report 15: roster."""
from textkit.render import render_cell

ROWS = [('omega63', 269), ('rho98', 346), ('kappa36', 224)]


def render():
    lines = [render_cell(width=18, fill="=", value="ROSTER")]
    for name, qty in ROWS:
        lines.append(render_cell(width=12, fill=" ", value=name) + render_cell(6, "-", str(qty)))
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
