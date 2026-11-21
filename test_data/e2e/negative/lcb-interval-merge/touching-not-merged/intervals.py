"""Booking blocks."""


def merge_blocks(intervals, tolerance=0):
    """Merge half-open intervals (start, end, weight) into blocks (start, end, peak)."""
    if tolerance < 0:
        raise ValueError("tolerance must not be negative")
    items = []
    for start, end, weight in intervals:
        if end < start:
            raise ValueError("interval ends before it starts")
        if weight < 0:
            raise ValueError("weight must not be negative")
        if end > start:
            items.append((start, end, weight))
    items.sort()
    blocks = []
    group = []
    block_end = None
    for item in items:
        if group and item[0] - block_end < tolerance:
            group.append(item)
            block_end = max(block_end, item[1])
        else:
            if group:
                blocks.append(_summarise(group, block_end))
            group = [item]
            block_end = item[1]
    if group:
        blocks.append(_summarise(group, block_end))
    return blocks


def _summarise(group, block_end):
    events = []
    for start, end, weight in group:
        events.append((start, 1, weight))
        events.append((end, 0, -weight))
    events.sort()
    current = peak = 0
    for _, _, delta in events:
        current += delta
        peak = max(peak, current)
    return (group[0][0], block_end, peak)
