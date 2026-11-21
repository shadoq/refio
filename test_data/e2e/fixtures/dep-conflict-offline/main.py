import bootstrap  # noqa: F401  (sets up sys.path)

import heatmap
import routeplan

print("route legs:", routeplan.leg_lengths(routeplan.WAYPOINTS))
print("route length:", routeplan.total_length(routeplan.WAYPOINTS))
print("hot spot:", heatmap.hot_spot(heatmap.SAMPLES))
print("trail length:", heatmap.trail_length(heatmap.SAMPLES))
