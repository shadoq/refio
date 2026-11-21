"""geomlib 1.4: plain tuples."""
import math

__version__ = "1.4.0"


def distance(a, b):
    """Euclidean distance between two (x, y) tuples."""
    return math.hypot(a[0] - b[0], a[1] - b[1])


def path_length(points):
    """Length of the polyline through a list of (x, y) tuples."""
    return sum(distance(a, b) for a, b in zip(points, points[1:]))
