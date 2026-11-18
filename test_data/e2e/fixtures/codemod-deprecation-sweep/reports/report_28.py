"""Report 28: orders."""
import textkit.legacy as tk

ROWS = [('zeta51', 444), ('iota33', 398), ('delta60', 177), ('lambda50', 436), ('delta4', 14), ('beta87', 397)]


def render():
    lines = [tk.fmt_cell("ORDERS", 18, "=")]
    for name, qty in ROWS:
        lines.append(
            tk.fmt_cell(name, width=14)
            + tk.fmt_cell(str(qty), 4, ".")
        )
        lines.append(tk.fmt_cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
