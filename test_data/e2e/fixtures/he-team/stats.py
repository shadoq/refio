"""Team statistics module - two stubs, currently raising. Implement per docstrings."""

def median(values):
    """Middle value of a non-empty numeric list; for an even length, the average of
    the two middle values (always a float in that case).

    >>> median([1, 3, 5])
    3
    >>> median([1, 3, 5, 7])
    4.0
    >>> median([2])
    2
    """
    raise NotImplementedError("implement median")


def variance(values):
    """Population variance: the mean of squared deviations from the mean. Returns
    0.0 for a single-element list. A float, always.

    >>> variance([2, 4])
    1.0
    >>> variance([5, 5, 5])
    0.0
    """
    raise NotImplementedError("implement variance")
