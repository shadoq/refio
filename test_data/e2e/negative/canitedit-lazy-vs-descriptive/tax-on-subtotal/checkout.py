from pricing import discount, subtotal, tax


def checkout_total(items, coupons, tax_rate):
    sub = subtotal(items)
    return discount(sub, coupons) + tax(sub, tax_rate)
