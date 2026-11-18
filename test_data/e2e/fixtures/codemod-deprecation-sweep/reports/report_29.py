"""Report 29: metrics."""
import textkit.legacy as tk

ROWS = [('rho79', 269), ('gamma96', 274), ('lambda65', 408), ('kappa33', 391), ('kappa45', 431)]


def render():
    lines = [tk.fmt_cell("METRICS", width=14, fill="=")]
    for name, qty in ROWS:
        lines.append(
            tk.fmt_cell(name, 8, "-")
            + tk.fmt_cell(value=str(qty), width=6, fill="*")
        )
        lines.append(tk.fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
