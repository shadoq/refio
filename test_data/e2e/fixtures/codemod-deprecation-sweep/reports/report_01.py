"""Report 01: shipments."""
from textkit.legacy import fmt_cell

ROWS = [('delta12', 285), ('kappa21', 209), ('rho48', 289)]


def render():
    lines = [fmt_cell("SHIPMENTS", 20, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 13)
            + fmt_cell(str(qty), width=7, fill="_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
