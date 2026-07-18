def to_roman(n):
    """Converts an integer to its Roman numeral string.

    Valid input is 1..3999; anything else raises ValueError.
    Examples: 1 -> "I", 4 -> "IV", 9 -> "IX", 14 -> "XIV", 1990 -> "MCMXC".
    """
    if not isinstance(n, int) or n < 1 or n > 3999:
        raise ValueError("expected an integer in 1..3999, got %r" % (n,))
    symbols = [
        (1000, "M"), (900, "CM"), (500, "D"), (400, "CD"),
        (100, "C"), (90, "XC"), (50, "L"), (40, "XL"),
        (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I"),
    ]
    out = []
    for value, symbol in symbols:
        while n >= value:
            out.append(symbol)
            n -= value
    return "".join(out)
