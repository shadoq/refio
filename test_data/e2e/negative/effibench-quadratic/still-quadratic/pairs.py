def count_close_pairs(values, k):
    """Count index pairs i < j with abs(values[i] - values[j]) <= k."""
    ordered = sorted(values)
    n = len(ordered)
    return sum(
        1
        for i in range(n)
        for j in range(i + 1, n)
        if ordered[j] - ordered[i] <= k
    )
