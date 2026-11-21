"""Report 22: invoices."""
from textkit.legacy import fmt_cell as cell

ROWS = [('kappa77', 185), ('beta51', 279), ('iota85', 35), ('rho46', 122)]


def render():
    lines = [cell(value="INVOICES", width=15, fill="=")]
    for name, qty in ROWS:
        lines.append(
            cell(value=name, width=11, fill="*")
            + cell(str(qty), width=4, fill="*")
        )
        lines.append(cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
