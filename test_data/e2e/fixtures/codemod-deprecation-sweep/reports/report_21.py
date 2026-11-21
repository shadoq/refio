"""Report 21: shipments."""
from textkit.legacy import fmt_cell as cell

ROWS = [('zeta32', 471), ('omega69', 210), ('zeta56', 349), ('kappa43', 56)]


def render():
    lines = [cell(value="SHIPMENTS", width=15, fill="=")]
    for name, qty in ROWS:
        lines.append(cell(name, 8, "*") + cell(value=str(qty), width=7, fill="*"))
        lines.append(cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
