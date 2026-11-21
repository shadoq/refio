"""Glob matching for slash separated paths.

A pattern and a path are split on ``/`` and matched segment by segment.

Inside a segment:

* ``*`` matches any run of characters except ``/`` (possibly empty)
* ``?`` matches exactly one character
* ``[abc]`` and ``[a-c]`` match one character of the set, ``[!abc]`` one character
  that is not in the set; a ``[`` without a closing ``]`` is a literal ``[``
* ``{a,b,c}`` matches any of the comma separated alternatives (the alternatives are
  themselves segment patterns; braces never contain ``/`` and are not nested)
* every other character, including ``.``, ``+``, ``(`` and ``)``, matches itself

A whole segment equal to ``**`` matches zero or more path segments, so
``src/**`` matches ``src`` and ``src/a/b``, and ``**/*.py`` matches ``a.py`` and
``x/y/a.py``.

Hidden names: a path segment that starts with ``.`` is only matched by a pattern
segment that starts with a literal ``.``. In particular ``*`` and ``?`` do not
match hidden names, and ``**`` does not descend into hidden directories.
"""


def match(pattern, path):
    """Return True when the whole path matches the pattern."""
    raise NotImplementedError


def filter(pattern, paths):
    """Return the paths that match the pattern, keeping their order."""
    raise NotImplementedError
