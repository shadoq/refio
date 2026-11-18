"""Report 15: roster."""
from textkit.legacy import fmt_cell

ROWS = [('omega63', 269), ('rho98', 346), ('kappa36', 224)]


def render():
    lines = [fmt_cell("ROSTER", width=18, fill="=")]
    for name, qty in ROWS:
        lines.append(fmt_cell(name, width=12) + fmt_cell(str(qty), 6, "-"))
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
