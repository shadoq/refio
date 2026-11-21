from pricing import discount, subtotal, tax


def checkout_total(items, coupons, tax_rate):
    sub = subtotal(items)
    sales_tax = tax(sub, tax_rate)
    return discount(sub, coupons) + sales_tax
