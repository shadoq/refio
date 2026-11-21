"""Report 24: sensors."""
from textkit.legacy import fmt_cell as cell

ROWS = [('theta50', 166), ('lambda13', 228), ('lambda47', 490)]


def render():
    lines = [cell(value="SENSORS", width=20, fill="=")]
    for name, qty in ROWS:
        lines.append(cell(value=name, width=14) + cell(str(qty), 6, "-"))
        lines.append(cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
