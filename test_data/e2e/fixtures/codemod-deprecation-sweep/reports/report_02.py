"""Report 02: invoices."""
from textkit.legacy import fmt_cell

ROWS = [('delta19', 67), ('omega62', 66), ('iota75', 319), ('beta91', 223), ('lambda7', 124), ('delta6', 216)]


def render():
    lines = [fmt_cell("INVOICES", 16, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 8, "*")
            + fmt_cell(str(qty), width=8, fill="_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
