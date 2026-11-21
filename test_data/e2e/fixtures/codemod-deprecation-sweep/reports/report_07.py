"""Report 07: audit."""
from textkit.legacy import fmt_cell

ROWS = [('zeta23', 455), ('omega95', 368), ('gamma29', 267)]


def render():
    lines = [fmt_cell("AUDIT", width=13, fill="=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 8, "*")
            + fmt_cell(value=str(qty), width=5, fill="_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
