"""Report 17: audit."""
from textkit.legacy import fmt_cell

ROWS = [('iota55', 98), ('beta46', 160), ('beta38', 140), ('alpha86', 146), ('theta68', 323), ('kappa80', 246)]


def render():
    lines = [fmt_cell(value="AUDIT", width=18, fill="=")]
    for name, qty in ROWS:
        lines.append(
            fmt_cell(name, width=12)
            + fmt_cell(str(qty), width=6, fill="*")
        )
        lines.append(fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
