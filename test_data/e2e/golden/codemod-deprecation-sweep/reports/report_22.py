"""Report 22: invoices."""
from textkit.render import render_cell as cell

ROWS = [('kappa77', 185), ('beta51', 279), ('iota85', 35), ('rho46', 122)]


def render():
    lines = [cell(value="INVOICES", width=15, fill="=")]
    for name, qty in ROWS:
        lines.append(
            cell(value=name, width=11, fill="*")
            + cell(width=4, fill="*", value=str(qty))
        )
        lines.append(cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
