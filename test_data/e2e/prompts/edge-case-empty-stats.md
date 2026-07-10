`summarize` in `src/stats.py` crashes with ZeroDivisionError on an empty list, but its
docstring says an empty input is valid and must yield `{"count": 0, "mean": None}`.
Handle the edge case in `src/stats.py` so `python3 -m unittest test_stats` passes.
Do not edit `test_stats.py`.
