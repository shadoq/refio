"""geomlib 2.0: Point objects. The tuple based helpers of 1.x were removed."""
import math
from dataclasses import dataclass

__version__ = "2.0.0"


@dataclass(frozen=True)
class Point:
    x: float
    y: float


def dist(p, q):
    """Euclidean distance between two Points."""
    return math.hypot(p.x - q.x, p.y - q.y)


def path_length(points):
    """Length of the polyline through a list of Points."""
    return sum(dist(a, b) for a, b in zip(points, points[1:]))


def centroid(points):
    """Mean position of a list of Points, as a Point."""
    n = len(points)
    return Point(sum(p.x for p in points) / n, sum(p.y for p in points) / n)
