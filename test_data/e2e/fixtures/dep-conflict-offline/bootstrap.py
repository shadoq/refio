"""Puts the vendored packages on sys.path. There is no network and no pip here."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor_pkgs")

for entry in ("routeplan", "heatmap", "geomlib-1.4"):
    sys.path.insert(0, os.path.join(VENDOR, entry))
