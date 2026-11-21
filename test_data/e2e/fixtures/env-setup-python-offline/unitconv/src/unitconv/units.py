_TO_METERS = {
    "m": 1.0,
    "km": 1000.0,
    "mi": 1609.344,
    "ft": 0.3048,
}


def supported_units():
    return sorted(_TO_METERS)


def convert(value, src, dst):
    """Convert a length between two of the supported units."""
    try:
        factor = _TO_METERS[src] / _TO_METERS[dst]
    except KeyError as err:
        raise ValueError("unsupported unit: %s" % err.args[0])
    return value * factor
