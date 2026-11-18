"""Report 26: budget."""
import textkit.render as tk

ROWS = [('theta35', 199), ('rho34', 114), ('zeta54', 232)]


def render():
    lines = [tk.render_cell(21, "=", "BUDGET")]
    for name, qty in ROWS:
        lines.append(
            tk.render_cell(width=13, fill=" ", value=name)
            + tk.render_cell(width=8, fill="*", value=str(qty))
        )
        lines.append(tk.render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
