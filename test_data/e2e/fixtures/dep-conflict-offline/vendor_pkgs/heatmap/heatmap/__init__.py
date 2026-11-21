"""heatmap 0.9: written against geomlib 2.x (Point objects)."""
import geomlib

SAMPLES = [(0, 0), (6, 0), (6, 6)]


def hot_spot(samples):
    c = geomlib.centroid([geomlib.Point(x, y) for x, y in samples])
    return (c.x, c.y)


def trail_length(samples):
    return geomlib.path_length([geomlib.Point(x, y) for x, y in samples])
