Our support team exports an activity log from the ticket tool as CSV (see `data/export.csv` for a sample) and wants a small JSON summary of it for the weekly report.

`SPEC.md` describes the input and the exact output. Please implement `summarize(path)` and `main(argv)` in `summarize.py` accordingly, using only the standard library. `python -m unittest discover -s tests` must pass. Do not change `SPEC.md`, the sample export or anything under `tests/`.
