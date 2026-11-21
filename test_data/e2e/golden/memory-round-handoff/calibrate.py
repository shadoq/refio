"""Conversion of raw ADC counts to engineering units for the HB-6 board."""

CALIBRATION = {0: (2.04, 12), 1: (1.26, -20), 2: (4.62, 17), 3: (1.65, 5), 4: (3.88, 11), 5: (4.69, 0)}


def convert(channel, raw):
    """Return the calibrated value for a raw reading of the given channel (0..5)."""
    gain, offset = CALIBRATION[channel]
    return raw * gain + offset
