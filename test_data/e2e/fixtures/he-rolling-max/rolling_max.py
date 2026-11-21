"""Rolling window maximum - complete the documented function.

The docstring is the contract; the unit tests in test_rolling_max.py are the
acceptance bar (they are not to be modified).
"""


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
    raise NotImplementedError("implement rolling_max")
