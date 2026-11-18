"""Report 24: sensors."""
from textkit.render import render_cell as cell

ROWS = [('theta50', 166), ('lambda13', 228), ('lambda47', 490)]


def render():
    lines = [cell(value="SENSORS", width=20, fill="=")]
    for name, qty in ROWS:
        lines.append(cell(value=name, width=14, fill=" ") + cell(6, "-", str(qty)))
        lines.append(cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
