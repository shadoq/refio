"""Conversion of raw ADC counts to engineering units for the HB-6 board."""

CALIBRATION = {0: (1.0, 0), 1: (1.0, 0), 2: (1.0, 0), 3: (1.0, 0), 4: (1.0, 0), 5: (1.0, 0)}


def convert(channel, raw):
    """Return the calibrated value for a raw reading of the given channel (0..5)."""
    gain, offset = CALIBRATION[channel]
    return raw * gain + offset
