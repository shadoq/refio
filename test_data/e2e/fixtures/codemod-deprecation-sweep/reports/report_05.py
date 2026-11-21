"""Report 05: roster."""
from textkit.legacy import fmt_cell

ROWS = [('kappa69', 211), ('zeta82', 154), ('sigma71', 339), ('gamma63', 63), ('zeta42', 189), ('delta5', 339)]


def render():
    lines = [fmt_cell("ROSTER", 21, "=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, 13, "-")
            + fmt_cell(str(qty), 8)
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
