"""Report 27: audit."""
import textkit.legacy as tk

ROWS = [('beta11', 277), ('theta29', 2), ('zeta90', 211)]


def render():
    lines = [tk.fmt_cell("AUDIT", 22, "=")]
    for name, qty in ROWS:
        lines.append(tk.fmt_cell(name, width=14, fill="_") + tk.fmt_cell(str(qty), width=8))
        lines.append(tk.fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
