def shipping_fee(weight_kg):
    """Shipping is free at 50 kg or more; otherwise it costs 15."""
    if weight_kg >= 50:
        return 0
    return 15
