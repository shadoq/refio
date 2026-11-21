def count_close_pairs(values, k):
    """Count index pairs i < j with abs(values[i] - values[j]) <= k."""
    ordered = sorted(values)
    count = 0
    lo = 0
    for hi in range(len(ordered)):
        while ordered[hi] - ordered[lo] >= k:
            lo += 1
        count += hi - lo
    return count
