"""Report 25: roster."""
from textkit.render import render_cell as cell

ROWS = [('alpha6', 106), ('sigma30', 325), ('iota32', 197), ('rho41', 246)]


def render():
    lines = [cell(width=15, fill="=", value="ROSTER")]
    for name, qty in ROWS:
        lines.append(
            cell(value=name, width=10, fill="_")
            + cell(value=str(qty), width=5, fill="*")
        )
        lines.append(cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
