# Route and heat tools

`routeplan` and `heatmap` are vendored third-party packages in `vendor_pkgs/`, together with two releases of `geomlib`. There is no network access and nothing can be installed, so everything has to work from these local copies. Treat `vendor_pkgs/` as read-only. `bootstrap.py` is our own glue code; `python main.py` runs both tools.
