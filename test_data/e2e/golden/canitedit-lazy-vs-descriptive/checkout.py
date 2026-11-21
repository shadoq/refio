from pricing import discount, subtotal, tax


def checkout_total(items, coupons, tax_rate):
    discounted = discount(subtotal(items), coupons)
    return discounted + tax(discounted, tax_rate)
