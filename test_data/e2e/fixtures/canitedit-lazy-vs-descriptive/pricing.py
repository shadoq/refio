def subtotal(items):
    return sum(item.unit_cents * item.qty for item in items)


def discount(amount, coupons):
    total = amount
    for coupon in coupons:
        if coupon.kind == "percent":
            total -= round(total * coupon.value / 100)
        else:
            total -= coupon.value
    return total


def tax(amount, rate_percent):
    return round(amount * rate_percent / 100)
