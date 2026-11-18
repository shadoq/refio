"""Plain text tables."""


def _pad(cell, width, how):
    extra = width - len(cell)
    if how == "r":
        return " " * extra + cell
    if how == "c":
        left = extra // 2
        return " " * left + cell + " " * (extra - left)
    return cell + " " * extra


def render(rows, headers=None, align=None):
    align = align or ""
    if any(a not in "lrc" for a in align):
        raise ValueError("bad align: %r" % (align,))
    conv = [["" if c is None else str(c) for c in r] for r in rows]
    head = None if headers is None else ["" if c is None else str(c) for c in headers]
    ncols = max([len(r) for r in conv] + [len(head) if head else 0])
    if ncols == 0:
        return ""
    conv = [r + [""] * (ncols - len(r)) for r in conv]
    if head is not None:
        head = head + [""] * (ncols - len(head))
    widths = [max([len(r[i]) for r in conv] + ([len(head[i])] if head else [])) for i in range(ncols)]
    hows = [align[i] if i < len(align) else "l" for i in range(ncols)]
    lines = []
    if head is not None:
        lines.append(" | ".join(_pad(head[i], widths[i], hows[i]) for i in range(ncols)).rstrip())
        lines.append("-+-".join("-" * w for w in widths))
    for r in conv:
        lines.append(" | ".join(_pad(r[i], widths[i], hows[i]) for i in range(ncols)).rstrip())
    return "\n".join(lines)
