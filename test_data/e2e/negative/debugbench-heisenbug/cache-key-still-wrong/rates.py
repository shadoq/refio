"""Shipping rate calculator."""

RATE_TABLE = {
    "EU": {1: 500, 5: 900, 20: 2000},
    "US": {1: 900, 5: 1600, 20: 3500},
}

BULK_FEE_CENTS = 250
BULK_PARCEL_COUNT = 3


def weight_band(weight_kg):
    """Smallest rate band (1, 5 or 20 kg) that fits the weight."""
    if weight_kg <= 0:
        raise ValueError("weight must be positive")
    for band in (1, 5, 20):
        if weight_kg <= band:
            return band
    raise ValueError("parcel is too heavy")


class RateCalculator:
    """Prices parcels by destination and weight band; express costs 50 percent more."""

    def __init__(self, table=None):
        self.table = table or RATE_TABLE
        self._cache = {}

    def rate(self, weight_kg, destination, express=False):
        """Price in cents of one parcel."""
        band = weight_band(weight_kg)
        key = (destination, band)
        if key in self._cache:
            return self._cache[key]
        price = self.table[destination][band]
        if express:
            price = price * 3 // 2
        self._cache[key] = price
        return price

    def quote(self, weights, destination, express=False, fees=None):
        """Total price in cents for a list of parcel weights.

        `fees` are extra fees in cents added once to the total. A bulk fee is added
        when there are more than BULK_PARCEL_COUNT parcels.
        """
        extra = list(fees) if fees else []
        total = sum(self.rate(w, destination, express) for w in weights)
        if len(weights) > BULK_PARCEL_COUNT:
            extra.append(BULK_FEE_CENTS)
        return total + sum(extra)
