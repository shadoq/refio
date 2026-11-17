#!/usr/bin/env python3
"""Ingest pipeline for the metrics relay (legacy procedural implementation).

Records arrive from the edge collectors; each one is validated, classified
and folded into the running STATE, with COUNTER handing out sequence numbers
and LOG keeping one entry per record for the nightly report.

Run as a CLI, one JSON record per argument:

    python3 pipeline.py '{"source": "edge-1", "metric": "cpu", "value": 0.7, "ts": 1690000000}'

The fold summary is printed to stdout as JSON.
"""

import json
import sys

# Running fold state for the relay.
STATE = {
    "received": 0,
    "accepted": 0,
    "rejected": 0,
    "by_source": {},
}

# Sequence numbers handed out by process_record.
COUNTER = 0

# One entry per processed record, in arrival order.
LOG = []

# Metrics expressed as a 0..1 fraction; every other metric must be >= 0.
RATIO_METRICS = ("cpu", "mem", "disk")


def validate(record):
    """Return a rejection reason for a bad record, or None when it is fine."""
    if not isinstance(record, dict):
        return "record must be an object"
    for field in ("source", "metric", "value", "ts"):
        if field not in record:
            return "missing field: " + field
    if not isinstance(record["source"], str) or not record["source"]:
        return "bad field: source"
    if not isinstance(record["metric"], str) or not record["metric"]:
        return "bad field: metric"
    value = record["value"]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return "bad field: value"
    ts = record["ts"]
    if not isinstance(ts, int) or isinstance(ts, bool):
        return "bad field: ts"
    if record["metric"] in RATIO_METRICS:
        if value < 0 or value > 1:
            return "value out of range: " + record["metric"] + " must be within 0..1"
    elif value < 0:
        return "value out of range: " + record["metric"] + " must be >= 0"
    return None


def process_record(record):
    """Validate and fold one record into the running state."""
    global COUNTER
    COUNTER = COUNTER + 1
    reason = validate(record)
    STATE["received"] = STATE["received"] + 1
    if reason is None:
        STATE["accepted"] = STATE["accepted"] + 1
        source = record["source"]
        if source not in STATE["by_source"]:
            STATE["by_source"][source] = 0
        STATE["by_source"][source] = STATE["by_source"][source] + 1
        LOG.append({"seq": COUNTER, "status": "accepted", "source": source})
        return {"status": "accepted", "reason": None, "seq": COUNTER}
    else:
        STATE["rejected"] = STATE["rejected"] + 1
        source = None
        if isinstance(record, dict) and isinstance(record.get("source"), str) and record["source"]:
            source = record["source"]
            if source not in STATE["by_source"]:
                STATE["by_source"][source] = 0
            STATE["by_source"][source] = STATE["by_source"][source] + 1
        LOG.append({"seq": COUNTER, "status": "rejected", "source": source, "reason": reason})
        return {"status": "rejected", "reason": reason, "seq": COUNTER}


def replay(records):
    """Process a batch of records and return the per-record results."""
    results = []
    for record in records:
        results.append(process_record(record))
    return results


def summarize():
    """A fresh snapshot of the running state (never the live dicts)."""
    received = STATE["received"]
    if received:
        rate = round(STATE["accepted"] / received, 3)
    else:
        rate = 0.0
    return {
        "received": received,
        "accepted": STATE["accepted"],
        "rejected": STATE["rejected"],
        "by_source": dict(STATE["by_source"]),
        "records": len(LOG),
        "acceptance_rate": rate,
    }


def reset():
    """Clear every trace of processed records."""
    global COUNTER
    STATE["received"] = 0
    STATE["accepted"] = 0
    STATE["rejected"] = 0
    STATE["by_source"] = {}
    del LOG[:]
    COUNTER = 0


def main(argv=None):
    """Run one CLI batch: fold every argument, print the summary."""
    if argv is None:
        argv = sys.argv[1:]
    if not argv:
        sys.stderr.write("usage: pipeline.py '<json-record>'...\n")
        return 2
    reset()
    for arg in argv:
        try:
            record = json.loads(arg)
        except ValueError:
            # not JSON: hand the raw argument through, it gets rejected
            # with the usual reason
            record = arg
        process_record(record)
    print(json.dumps(summarize(), sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
