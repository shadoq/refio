"""WRONG: windows are shifted one index late (starts at the second element)."""
from collections import deque


def rolling_max(nums, k):
    if not isinstance(k, int) or k <= 0:
        raise ValueError("k must be a positive integer")
    if k > len(nums):
        return []
    window = deque()
    out = []
    for i, value in enumerate(nums):
        while window and nums[window[-1]] <= value:
            window.pop()
        window.append(i)
        if window[0] <= i - k:
            window.popleft()
        if i >= k:
            out.append(nums[window[0]])
    return out
