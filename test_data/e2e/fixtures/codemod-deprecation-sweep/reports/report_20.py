"""Report 20: inventory."""
from textkit.legacy import fmt_cell

ROWS = [('alpha60', 397), ('kappa75', 92), ('omega23', 308)]


def render():
    lines = [fmt_cell("INVENTORY", 16, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, width=10, fill="*")
            + fmt_cell(str(qty), width=6, fill="-")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
