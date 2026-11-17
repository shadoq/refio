"""WRONG: k <= 0 silently returns [] instead of raising ValueError."""
from collections import deque


def rolling_max(nums, k):
    if not isinstance(k, int) or k <= 0:
        return []
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
        if i >= k - 1:
            out.append(nums[window[0]])
    return out
