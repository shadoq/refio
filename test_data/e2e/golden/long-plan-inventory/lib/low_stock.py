def low_stock(inv):
    return sorted(k for k, v in inv.items() if v["qty"] <= v["min"])
