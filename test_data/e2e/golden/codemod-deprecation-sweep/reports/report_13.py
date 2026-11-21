"""Report 13: tickets."""
from textkit.render import render_cell

ROWS = [('omega89', 420), ('rho98', 427), ('rho24', 291), ('zeta4', 197), ('alpha50', 126), ('omega1', 326)]


def render():
    lines = [render_cell(14, "=", "TICKETS")]
    for name, qty in ROWS:
        lines.append(
            render_cell(value=name, width=10, fill=".")
            + render_cell(4, "_", str(qty))
        )
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
