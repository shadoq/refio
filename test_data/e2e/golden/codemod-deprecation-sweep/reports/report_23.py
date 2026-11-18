"""Report 23: tickets."""
from textkit.render import render_cell as cell

ROWS = [('rho58', 245), ('gamma55', 160), ('alpha84', 484), ('kappa45', 404), ('sigma48', 202), ('kappa36', 34)]


def render():
    lines = [cell(18, "=", "TICKETS")]
    for name, qty in ROWS:
        lines.append(
            cell(value=name, width=12, fill="_")
            + cell(6, "-", str(qty))
        )
        lines.append(cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
