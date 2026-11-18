"""Report 25: roster."""
from textkit.legacy import fmt_cell as cell

ROWS = [('alpha6', 106), ('sigma30', 325), ('iota32', 197), ('rho41', 246)]


def render():
    lines = [cell("ROSTER", width=15, fill="=")]
    for name, qty in ROWS:
        lines.append(
            cell(value=name, width=10, fill="_")
            + cell(value=str(qty), width=5, fill="*")
        )
        lines.append(cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
