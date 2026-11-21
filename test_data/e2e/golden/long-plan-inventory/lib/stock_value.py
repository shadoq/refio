def stock_value(inv):
    return round(sum(v["qty"] * v["price"] for v in inv.values()), 2)
