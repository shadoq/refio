from lib.formatting import format_price


def receipt(prices):
    return [format_price(p, currency="USD") for p in prices]


lines = receipt([3.5, 10])
assert lines == ["USD 3.50", "USD 10.00"], lines
print("OK")
