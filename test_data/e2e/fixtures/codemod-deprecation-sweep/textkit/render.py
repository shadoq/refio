"""Current cell rendering API."""


def render_cell(width, fill, value):
    """Left-justify str(value) in a cell of `width` characters padded with `fill`.

    All three arguments are required.
    """
    if len(fill) != 1:
        raise ValueError("fill must be a single character")
    return str(value).ljust(width, fill)
