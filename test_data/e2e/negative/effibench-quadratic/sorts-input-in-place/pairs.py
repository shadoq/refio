def count_close_pairs(values, k):
    """Count index pairs i < j with abs(values[i] - values[j]) <= k."""
    values.sort()
    count = 0
    lo = 0
    for hi in range(len(values)):
        while values[hi] - values[lo] > k:
            lo += 1
        count += hi - lo
    return count
