"""Report 26: budget."""
import textkit.legacy as tk

ROWS = [('theta35', 199), ('rho34', 114), ('zeta54', 232)]


def render():
    lines = [tk.fmt_cell("BUDGET", 21, "=")]
    for name, qty in ROWS:
        lines.append(
            tk.fmt_cell(name, width=13)
            + tk.fmt_cell(str(qty), width=8, fill="*")
        )
        lines.append(tk.fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
