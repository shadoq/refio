"""Report 08: orders."""
from textkit.render import render_cell

ROWS = [('sigma11', 363), ('beta7', 487), ('iota92', 424), ('gamma87', 303), ('beta76', 177), ('iota2', 277)]


def render():
    lines = [render_cell(20, "=", "ORDERS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(14, "_", name)
            + render_cell(value=str(qty), width=6, fill="*")
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
