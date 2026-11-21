from lib.low_stock import low_stock


def restock_plan(inv, target):
    return {sku: target - inv[sku]["qty"] for sku in low_stock(inv) if target > inv[sku]["qty"]}
