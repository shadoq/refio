A local shop is running at {{FIXTURE_SERVER}} - it has an HTML landing page, a
machine-readable catalog at `/catalog` and per-product pages. The procurement mission is
fixed in `docs/mission.md`: a hard budget of 80.00 EUR, exactly one item per list
category (keyboard, mouse, monitor, dock), and among the feasible carts the one with the
highest total rating must be chosen (the catalog's optimum is unique).

Browse the shop over HTTP, then write the chosen cart to `cart/cart.json` as
`{"items": ["<id>", ...]}` (one product id per list category, sorted).
`python3 checks.py` must exit 0 - it brute-forces the catalog and accepts only the exact
optimum. Use HTTP requests for the shop; do not modify the server, its catalog, the
mission or the gate.
