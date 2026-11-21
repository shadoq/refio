from dataclasses import dataclass


@dataclass(frozen=True)
class Item:
    sku: str
    unit_cents: int
    qty: int = 1


@dataclass(frozen=True)
class Coupon:
    code: str
    kind: str  # "percent" or "fixed"
    value: int  # percent points for "percent", cents for "fixed"
