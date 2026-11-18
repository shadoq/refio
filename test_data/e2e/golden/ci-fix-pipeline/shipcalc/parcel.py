"""Parcel dimensions, billable weight and quotes."""
from decimal import ROUND_HALF_UP, Decimal

from .rates import rate_for

__all__ = ["billable_weight", "quote"]

VOLUMETRIC_DIVISOR = 5000


def billable_weight(weight_kg, length_cm, width_cm, height_cm):
    """The carrier bills the larger of actual and volumetric weight."""
    volumetric = length_cm * width_cm * height_cm / VOLUMETRIC_DIVISOR
    return max(weight_kg, volumetric)


def quote(weight_kg, length_cm, width_cm, height_cm):
    billable = Decimal(
        str(billable_weight(weight_kg, length_cm, width_cm, height_cm)))
    cost = billable * rate_for(billable)
    return cost.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
