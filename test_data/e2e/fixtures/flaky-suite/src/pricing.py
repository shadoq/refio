"""Pricing helpers for the billing tier."""
import random


def happy_hour_discount(price, hour):
    """20% off during happy hour (17:00-19:00)."""
    if 17 <= hour < 19:
        return round(price * 0.8, 2)
    return price


def audit_sample(records):
    """Random 30% audit sample of the transaction log."""
    return [r for r in records if random.random() < 0.3]
