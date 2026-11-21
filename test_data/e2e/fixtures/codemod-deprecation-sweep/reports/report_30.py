"""Report 30: inventory."""
import textkit.legacy as tk

ROWS = [('kappa99', 307), ('gamma84', 429), ('delta58', 483)]


def render():
    lines = [tk.fmt_cell("INVENTORY", 22, "=")]
    for name, qty in ROWS:
        lines.append(tk.fmt_cell(name, 14, ".") + tk.fmt_cell(value=str(qty), width=8, fill="-"))
        lines.append(tk.fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
