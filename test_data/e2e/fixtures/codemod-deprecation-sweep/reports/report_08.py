"""Report 08: orders."""
from textkit.legacy import fmt_cell

ROWS = [('sigma11', 363), ('beta7', 487), ('iota92', 424), ('gamma87', 303), ('beta76', 177), ('iota2', 277)]


def render():
    lines = [fmt_cell("ORDERS", 20, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 14, "_")
            + fmt_cell(value=str(qty), width=6, fill="*")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
