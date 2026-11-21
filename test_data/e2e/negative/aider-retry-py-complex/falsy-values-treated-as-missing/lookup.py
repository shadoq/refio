"""Path lookup in nested dicts and lists."""


def resolve(context, path):
    """Return the value at the dotted path or raise KeyError(path)."""
    current = context
    for part in path.split("."):
        if isinstance(current, dict):
            if part not in current:
                raise KeyError(path)
            current = current[part]
        elif isinstance(current, (list, tuple)):
            if not (part.isascii() and part.isdigit()) or int(part) >= len(current):
                raise KeyError(path)
            current = current[int(part)]
        else:
            raise KeyError(path)
    if not current and current is not None:
        raise KeyError(path)
    return current
