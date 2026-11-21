from app import config


def shipping_cost(total):
    return 0 if total >= config.FREE_SHIPPING_THRESHOLD else config.SHIPPING_FEE
