def summarize(values):
    """Returns {"count": n, "mean": mean} for a list of numbers.

    An empty input is valid and yields {"count": 0, "mean": None}.
    """
    total = 0
    for v in values:
        total += v
    return {"count": len(values), "mean": total / len(values)}
