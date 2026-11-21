"""Report 11: shipments."""
from textkit.legacy import fmt_cell

ROWS = [('omega4', 213), ('gamma85', 227), ('kappa88', 227), ('lambda34', 92), ('beta60', 340)]


def render():
    lines = [fmt_cell("SHIPMENTS", 17, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 10)
            + fmt_cell(str(qty), 7, "_")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
