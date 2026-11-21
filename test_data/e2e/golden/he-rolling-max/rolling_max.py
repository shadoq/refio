"""Rolling window maximum - complete the documented function.

The docstring is the contract; the unit tests in test_rolling_max.py are the
acceptance bar (they are not to be modified).
"""
from collections import deque


def rolling_max(nums, k):
    """Return the maximum of each contiguous window of size `k`, in order.

    >>> rolling_max([3, 1, 4, 1, 5], 2)
    [3, 4, 4, 5]
    >>> rolling_max([4], 1)
    [4]
    >>> rolling_max([1, 2], 5)
    []
    >>> rolling_max([2, 1, 3], 0)
    Traceback (most recent call last):
        ...
    ValueError
    """
    if not isinstance(k, int) or isinstance(k, bool) or k <= 0:
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
        if i >= k - 1:
            out.append(nums[window[0]])
    return out
