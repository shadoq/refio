"""Report 23: tickets."""
from textkit.legacy import fmt_cell as cell

ROWS = [('rho58', 245), ('gamma55', 160), ('alpha84', 484), ('kappa45', 404), ('sigma48', 202), ('kappa36', 34)]


def render():
    lines = [cell("TICKETS", 18, "=")]
    for name, qty in ROWS:
        lines.append(
            cell(value=name, width=12, fill="_")
            + cell(str(qty), 6, "-")
        )
        lines.append(cell(f"{qty:04d}", 6, "0") + "|")
    return "\n".join(lines) + "\n"
