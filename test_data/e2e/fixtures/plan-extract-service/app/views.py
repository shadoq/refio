TAX_RATE = 0.23


def checkout_view(cart, coupon=None):
    subtotal = sum(item["price"] * item["qty"] for item in cart)
    if coupon == "SAVE10":
        subtotal = subtotal * 0.9
    total = subtotal * (1 + TAX_RATE)
    return {"total": round(total, 2)}


def invoice_view(order):
    subtotal = sum(line["price"] * line["qty"] for line in order["lines"])
    if order.get("coupon") == "SAVE10":
        subtotal = subtotal * 0.9
    total = subtotal * (1 + TAX_RATE)
    return {"number": order["number"], "total": round(total, 2)}
