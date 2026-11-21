"""Deprecated helpers kept for external callers. Do not edit."""
import warnings


def fmt_cell(value, width, fill=" "):
    """Deprecated: use textkit.render.render_cell."""
    warnings.warn("fmt_cell is deprecated, use textkit.render.render_cell",
                  DeprecationWarning, stacklevel=2)
    return str(value).ljust(width, fill)
