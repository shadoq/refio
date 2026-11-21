"""Report 18: orders."""
from textkit.legacy import fmt_cell

ROWS = [('iota99', 203), ('rho13', 347), ('delta76', 163), ('delta95', 413), ('gamma68', 370), ('rho53', 111)]


def render():
    lines = [fmt_cell("ORDERS", 18, "=")]
    for name, qty in ROWS:
        lines.append(fmt_cell(name, width=14, fill="_") + fmt_cell(str(qty), 4, "-"))
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
