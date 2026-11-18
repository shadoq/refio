"""Report 12: invoices."""
from textkit.legacy import fmt_cell

ROWS = [('gamma19', 207), ('delta78', 16), ('sigma33', 334), ('kappa69', 303), ('delta83', 79)]


def render():
    lines = [fmt_cell("INVOICES", 18, "=")]
    for name, qty in ROWS:
        lines.append(fmt_cell(value=name, width=11, fill="-") + fmt_cell(value=str(qty), width=7))
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
