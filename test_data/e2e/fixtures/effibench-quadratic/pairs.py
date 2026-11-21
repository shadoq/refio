def count_close_pairs(values, k):
    """Count index pairs i < j with abs(values[i] - values[j]) <= k."""
    count = 0
    n = len(values)
    for i in range(n):
        for j in range(i + 1, n):
            if abs(values[i] - values[j]) <= k:
                count += 1
    return count
