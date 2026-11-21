`count_close_pairs` in `pairs.py` gives correct answers but is far too slow: our nightly job calls it with a couple of hundred thousand values and it never finishes. Make it fast enough for that size without changing what it returns or its signature.

`python -m unittest test_pairs` must pass, including the timing test. Do not edit the tests.
