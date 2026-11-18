"""Report 28: orders."""
import textkit.render as tk

ROWS = [('zeta51', 444), ('iota33', 398), ('delta60', 177), ('lambda50', 436), ('delta4', 14), ('beta87', 397)]


def render():
    lines = [tk.render_cell(18, "=", "ORDERS")]
    for name, qty in ROWS:
        lines.append(
            tk.render_cell(width=14, fill=" ", value=name)
            + tk.render_cell(4, ".", str(qty))
        )
        lines.append(tk.render_cell(6, "0", f"{qty:04d}") + "|")
    return "\n".join(lines) + "\n"
