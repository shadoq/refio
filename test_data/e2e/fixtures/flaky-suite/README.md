# Pricing suite

`src/pricing.py` is the production module; `test/test_pricing.py` is its suite. CI fails
roughly one run in three, always in this suite, never the same test twice - locally the
suite usually passes, which is how it keeps getting merged.

The gate `checks.py` replays the suite under deterministic orderings and seeds; the flaky
suite cannot pass it.
