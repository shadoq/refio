"""Report 27: audit."""
import textkit.render as tk

ROWS = [('beta11', 277), ('theta29', 2), ('zeta90', 211)]


def render():
    lines = [tk.render_cell(22, "=", "AUDIT")]
    for name, qty in ROWS:
        lines.append(tk.render_cell(width=14, fill="_", value=name) + tk.render_cell(width=8, fill=" ", value=str(qty)))
        lines.append(tk.render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
