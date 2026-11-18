"""Report 17: audit."""
from textkit.render import render_cell

ROWS = [('iota55', 98), ('beta46', 160), ('beta38', 140), ('alpha86', 146), ('theta68', 323), ('kappa80', 246)]


def render():
    lines = [render_cell(value="AUDIT", width=18, fill="=")]
    for name, qty in ROWS:
        lines.append(
            render_cell(width=12, fill=" ", value=name)
            + render_cell(width=6, fill="*", value=str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
