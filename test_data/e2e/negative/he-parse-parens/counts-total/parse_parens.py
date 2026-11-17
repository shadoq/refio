"""WRONG: returns the total number of parentheses pairs per group, not the depth."""


def parse_nested_parens(s):
    groups = []
    for group in s.split():
        pairs = group.count("(")
        groups.append(pairs)
    return groups
