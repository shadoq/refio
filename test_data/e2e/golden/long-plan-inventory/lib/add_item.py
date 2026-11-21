def add_item(inv, sku, qty, price, min_qty=0):
    if qty < 0:
        raise ValueError("qty must not be negative")
    out = {k: dict(v) for k, v in inv.items()}
    old = out.get(sku, {"qty": 0, "min": min_qty})
    out[sku] = {"qty": old["qty"] + qty, "price": price, "min": old.get("min", min_qty)}
    return out
