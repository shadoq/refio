"""Report 29: metrics."""
import textkit.render as tk

ROWS = [('rho79', 269), ('gamma96', 274), ('lambda65', 408), ('kappa33', 391), ('kappa45', 431)]


def render():
    lines = [tk.render_cell(width=14, fill="=", value="METRICS")]
    for name, qty in ROWS:
        lines.append(
            tk.render_cell(8, "-", name)
            + tk.render_cell(value=str(qty), width=6, fill="*")
        )
        lines.append(tk.render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
