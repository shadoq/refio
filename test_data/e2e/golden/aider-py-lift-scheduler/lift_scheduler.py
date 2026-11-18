"""Deterministic simulation of a single lift car with collective control."""


def simulate(calls, buttons=(), start=1, travel=2, door=5):
    """Return the list of (time, floor) stops of the car."""
    pending = []
    for time, floor, direction in calls:
        if direction not in ("U", "D"):
            raise ValueError(f"bad direction: {direction!r}")
        pending.append((time, floor, direction))
    for time, floor in buttons:
        pending.append((time, floor, None))

    floor = start
    now = 0
    direction = None
    stops = []

    while pending:
        available = [r for r in pending if r[0] <= now]
        if not available:
            now = min(r[0] for r in pending)
            direction = None
            continue

        def ahead(d):
            if d == "U":
                return any(r[1] > floor for r in available)
            if d == "D":
                return any(r[1] < floor for r in available)
            return False

        served = [
            r for r in available
            if r[1] == floor and (r[2] is None or r[2] == direction or not ahead(direction))
        ]
        if served:
            stops.append((now, floor))
            for r in served:
                pending.remove(r)
            now += door
            continue

        if direction is None:
            nearest = min(available, key=lambda r: (abs(r[1] - floor), r[1]))
            direction = "U" if nearest[1] > floor else "D"
        elif not ahead(direction):
            direction = "D" if direction == "U" else "U"

        floor += 1 if direction == "U" else -1
        now += travel

    return stops
