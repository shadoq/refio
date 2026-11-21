"""routeplan 3.1: written against geomlib 1.x (tuples)."""
import geomlib

WAYPOINTS = [(0, 0), (3, 4), (3, 16)]


def leg_lengths(points):
    return [geomlib.dist(geomlib.Point(*a), geomlib.Point(*b)) for a, b in zip(points, points[1:])]


def total_length(points):
    return geomlib.path_length([geomlib.Point(*p) for p in points])
