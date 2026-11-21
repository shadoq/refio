"""Report 13: tickets."""
from textkit.legacy import fmt_cell

ROWS = [('omega89', 420), ('rho98', 427), ('rho24', 291), ('zeta4', 197), ('alpha50', 126), ('omega1', 326)]


def render():
    lines = [fmt_cell("TICKETS", 14, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(value=name, width=10, fill=".")
            + fmt_cell(str(qty), 4, "_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
