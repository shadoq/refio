"""Report 21: shipments."""
from textkit.render import render_cell as cell

ROWS = [('zeta32', 471), ('omega69', 210), ('zeta56', 349), ('kappa43', 56)]


def render():
    lines = [cell(value="SHIPMENTS", width=15, fill="=")]
    for name, qty in ROWS:
        lines.append(cell(8, "*", name) + cell(value=str(qty), width=7, fill="*"))
        lines.append(cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
