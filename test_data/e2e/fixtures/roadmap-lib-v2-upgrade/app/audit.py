"""Audit a stock file for bad rows."""
from recordio import read_records, Schema, RecordError

SCHEMA = Schema(["id", "qty"])


def audit_file(path):
    """Return a list of problems such as 'row 3: missing field'.

    A structurally broken file (wrong number of fields in a row) yields exactly one
    problem, for the first broken row, and nothing else.
    """
    try:
        rows = read_records(path)
    except RecordError as err:
        return ["row %d: %s" % (err.row, err.message)]
    problems = []
    for number, row in enumerate(rows, start=1):
        if not SCHEMA.validate(row):
            problems.append("row %d: missing field" % number)
    return problems
