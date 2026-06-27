def average(xs):
    # BUG: integer division truncates; average([1, 2]) should be 1.5, not 1.
    return sum(xs) // len(xs)
