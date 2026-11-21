def f(n):
    """n: integer, 1 <= n <= 999, no digit of n is 0."""
    digits = [int(d) for d in str(n)]
    return sum(d * d for d in digits) * 101 + digits[0] * 10 + digits[-1]


def g(text):
    """text: lowercase ASCII letters only, at least one letter."""
    out = []
    seen = set()
    for ch in text:
        if ch in seen:
            out.append(ch.upper())
        else:
            out.append(ch)
            seen.add(ch)
    return "".join(out[::-1]) + str(len(seen))


def h(xs):
    """xs: list of 4 integers, each in 1..9, strictly increasing."""
    acc = 0
    for i, x in enumerate(xs):
        acc = (acc * 7 + x * (i + 1)) % 1000
    return acc
