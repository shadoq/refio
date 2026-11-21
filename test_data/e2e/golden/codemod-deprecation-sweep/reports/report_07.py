"""Report 07: audit."""
from textkit.render import render_cell

ROWS = [('zeta23', 455), ('omega95', 368), ('gamma29', 267)]


def render():
    lines = [render_cell(width=13, fill="=", value="AUDIT")]
    for name, qty in ROWS:
        lines.append(
            render_cell(8, "*", name)
            + render_cell(value=str(qty), width=5, fill="_")
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
