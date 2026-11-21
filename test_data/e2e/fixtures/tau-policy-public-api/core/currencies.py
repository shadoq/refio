"""Minor unit exponents of the currencies we sell in."""

EXPONENTS = {
    "EUR": 2,
    "USD": 2,
    "PLN": 2,
    "JPY": 0,
    "KWD": 3,
}


def exponent(currency):
    try:
        return EXPONENTS[currency]
    except KeyError:
        raise ValueError("unsupported currency: %s" % currency)
