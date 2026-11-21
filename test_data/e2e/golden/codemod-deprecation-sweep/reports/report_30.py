"""Report 30: inventory."""
import textkit.render as tk

ROWS = [('kappa99', 307), ('gamma84', 429), ('delta58', 483)]


def render():
    lines = [tk.render_cell(22, "=", "INVENTORY")]
    for name, qty in ROWS:
        lines.append(tk.render_cell(14, ".", name) + tk.render_cell(value=str(qty), width=8, fill="-"))
        lines.append(tk.render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
