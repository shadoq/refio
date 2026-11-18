`textkit.legacy.fmt_cell` has been deprecated for a while and is about to be removed from our shared library, so every caller in `reports/` has to move to `textkit.render.render_cell`. Note that the argument order is different in the new function and `fill` has no default any more.

Migrate all thirty modules in `reports/` so that they no longer use or import `textkit.legacy`, and each report keeps producing exactly the same text. Leave the `textkit` package itself alone because other teams still use the old helper.

`python checks.py` verifies the result: it compares every report with the recorded baseline in `expected/`, treats deprecation warnings as errors and scans for leftovers of the old name. Do not edit the checks script or the baselines.
