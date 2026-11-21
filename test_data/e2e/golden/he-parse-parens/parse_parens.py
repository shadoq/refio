"""Nested parentheses depth parser - complete the documented function.

The docstring is the contract; the unit tests in test_parse_parens.py are the
acceptance bar (they are not to be modified).
"""


def parse_nested_parens(s):
    """For each space-separated group of parentheses, return its maximum nesting
    depth. Groups are guaranteed balanced; an empty input yields an empty list.

    >>> parse_nested_parens('(()()) ((())) () ((())()())')
    [2, 3, 1, 3]
    >>> parse_nested_parens('()')
    [1]
    >>> parse_nested_parens('')
    []
    """
    depths = []
    for group in s.split():
        depth = 0
        max_depth = 0
        for ch in group:
            if ch == "(":
                depth += 1
                max_depth = max(max_depth, depth)
            elif ch == ")":
                depth -= 1
        depths.append(max_depth)
    return depths
