def format_price(amount, *, currency):
    """v2 API: currency is a required keyword-only argument.

    In v1 the currency was the second positional argument; v2 made it
    keyword-only. This file is the vendored library and must not be edited.
    """
    return "%s %.2f" % (currency, amount)
