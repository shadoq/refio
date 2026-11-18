def subtotal(items):
    return sum(item.unit_cents * item.qty for item in items)


def _percent_of(amount, percent):
    # integer round-half-up, no float drift
    return (amount * percent + 50) // 100


def discount(amount, coupons):
    total = amount
    for coupon in coupons:
        if coupon.kind == "percent":
            total -= _percent_of(total, coupon.value)
    for coupon in coupons:
        if coupon.kind == "fixed":
            total -= coupon.value
    return max(total, 0)


def tax(amount, rate_percent):
    return _percent_of(amount, rate_percent)
