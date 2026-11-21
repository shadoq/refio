"""Report 20: inventory."""
from textkit.render import render_cell

ROWS = [('alpha60', 397), ('kappa75', 92), ('omega23', 308)]


def render():
    lines = [render_cell(16, "=", "INVENTORY")]
    for name, qty in ROWS:
        lines.append(
            render_cell(width=10, fill="*", value=name)
            + render_cell(width=6, fill="-", value=str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
