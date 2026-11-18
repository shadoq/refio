"""Puts the vendored packages on sys.path. There is no network and no pip here."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor_pkgs")

for entry in ("routeplan", "heatmap", "geomlib-2.0"):
    sys.path.insert(0, os.path.join(VENDOR, entry))

import geomlib  # noqa: E402

# routeplan still speaks the 1.x tuple API, heatmap the 2.x Point API: make one module serve both.
_dist = geomlib.dist
_path_length = geomlib.path_length


def _as_point(p):
    return p if isinstance(p, geomlib.Point) else geomlib.Point(*p)


def _distance(a, b):
    return _dist(_as_point(a), _as_point(b))


def _compat_path_length(points):
    return _path_length([_as_point(p) for p in points])


geomlib.distance = _distance
