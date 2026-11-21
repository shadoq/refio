"""Report 12: invoices."""
from textkit.render import render_cell

ROWS = [('gamma19', 207), ('delta78', 16), ('sigma33', 334), ('kappa69', 303), ('delta83', 79)]


def render():
    lines = [render_cell(18, "=", "INVOICES")]
    for name, qty in ROWS:
        lines.append(render_cell(value=name, width=11, fill="-") + render_cell(value=str(qty), width=7, fill=" "))
        lines.append(render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
