"""Half-open integer intervals [start, end).

An interval is a (start, end) tuple with start < end. A collection of intervals is
a list of such tuples in any order; overlapping or touching intervals describe the
same covered area as their merged form.
"""


def _validate(intervals):
    out = []
    for start, end in intervals:
        if start >= end:
            raise ValueError("empty or reversed interval: %r" % ((start, end),))
        out.append((start, end))
    return out


def merge(intervals):
    """Return the sorted list of disjoint intervals covering the same area.

    Intervals that overlap or touch (one ends where the next starts) are joined.
    """
    items = sorted(_validate(intervals))
    merged = []
    for start, end in items:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return merged


def total_length(intervals):
    """Length of the covered area (overlaps are counted once)."""
    return sum(end - start for start, end in merge(intervals))


def contains(intervals, point):
    """True when the point is inside one of the intervals (the end is excluded)."""
    return any(start <= point < end for start, end in intervals)


def gaps(intervals, lo, hi):
    """Return the uncovered parts of [lo, hi) as sorted disjoint intervals."""
    if lo >= hi:
        raise ValueError("lo must be below hi")
    cursor = lo
    result = []
    for start, end in merge(intervals):
        if end <= cursor:
            continue
        if start >= hi:
            break
        if start > cursor:
            result.append((cursor, start))
        cursor = max(cursor, end)
    if cursor < hi:
        result.append((cursor, hi))
    return result


def clip(intervals, lo, hi):
    """Return the merged intervals cut to [lo, hi); parts outside are dropped."""
    if lo >= hi:
        raise ValueError("lo must be below hi")
    out = []
    for start, end in merge(intervals):
        s, e = max(start, lo), min(end, hi)
        if s < e:
            out.append((s, e))
    return out


def subtract(a, b):
    """Return the area of a that is not covered by b, as disjoint intervals."""
    result = []
    cuts = merge(b)
    for start, end in merge(a):
        cur = start
        for cut_start, cut_end in cuts:
            if cut_end <= cur:
                continue
            if cut_start >= end:
                break
            if cut_start > cur:
                result.append((cur, cut_start))
            cur = max(cur, cut_end)
        if cur < end:
            result.append((cur, end))
    return result
