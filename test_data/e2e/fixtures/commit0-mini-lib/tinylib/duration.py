"""Durations such as ``1h30m``.

Units: ``w`` (7 days), ``d`` (24 hours), ``h``, ``m`` and ``s``.
"""


def parse(text):
    """Return the duration in whole seconds as an int.

    The text is one or more ``<number><unit>`` parts, optionally separated by
    whitespace, in any order, case-insensitive: ``1h30m``, ``2d 4h``, ``90S``. A
    number may have a decimal part (``1.5h`` is 5400). A unit may appear only once.
    Raises ValueError for empty text, a number without a unit, an unknown unit, a
    repeated unit, any other text, or a total that is not a whole number of seconds
    (``0.5s``).
    """
    raise NotImplementedError


def format(seconds, max_parts=None):
    """Format a non-negative int of seconds using the units d, h, m and s.

    Zero parts are left out and parts are not separated: ``93784`` is ``1d2h3m4s``
    and ``3600`` is ``1h``. ``0`` is ``0s``. When max_parts is given only the first
    max_parts non-zero parts are kept (the rest is cut off, not rounded):
    ``format(93784, 2)`` is ``1d2h``. Raises ValueError for a negative value or a
    value that is not an int (bool is not accepted either), and for max_parts below 1.
    """
    raise NotImplementedError
