"""Wires the three stages together: ingest -> transform -> load."""

from ingest import read_raw
from transform import transform
from load import load


def run():
    raw = read_raw()
    transformed = transform(raw)
    return load(transformed)


if __name__ == "__main__":
    print(run())
