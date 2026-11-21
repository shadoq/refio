def remove_item(inv, sku, qty):
    if sku not in inv:
        raise KeyError(sku)
    if qty > inv[sku]["qty"]:
        raise ValueError("not enough stock")
    out = {k: dict(v) for k, v in inv.items()}
    out[sku]["qty"] -= qty
    if out[sku]["qty"] == 0:
        del out[sku]
    return out
