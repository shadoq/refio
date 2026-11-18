"""Report 16: budget."""
from textkit.legacy import fmt_cell

ROWS = [('rho50', 280), ('sigma94', 287), ('gamma83', 338), ('iota9', 179), ('theta5', 240)]


def render():
    lines = [fmt_cell("BUDGET", width=19, fill="=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, width=12, fill=".")
            + fmt_cell(value=str(qty), width=7, fill="_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
