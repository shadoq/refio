# Metrics relay - ingest pipeline

`pipeline.py` folds the records arriving from the edge collectors into a
running tally: `process_record` validates and classifies one record, `replay`
drives a batch, `summarize` snapshots the tally (including the per-source
attribution and the rounded acceptance rate) and `reset` clears it. Rejected
records are attributed to their source whenever one can be read.

Run it as a CLI, one JSON record per argument:

    python3 pipeline.py '{"source": "edge-1", "metric": "cpu", "value": 0.7, "ts": 1}'

`checks.py` is the acceptance gate: `python3 checks.py`.
