from app import config


def with_tax(amount):
    return round(amount * (1 + config.TAX_RATE), 2)
