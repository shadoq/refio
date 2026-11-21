"""Report 02: invoices."""
from textkit.render import render_cell

ROWS = [('delta19', 67), ('omega62', 66), ('iota75', 319), ('beta91', 223), ('lambda7', 124), ('delta6', 216)]


def render():
    lines = [render_cell(16, "=", "INVOICES")]
    for name, qty in ROWS:
        lines.append(
            render_cell(8, "*", name)
            + render_cell(width=8, fill="_", value=str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
