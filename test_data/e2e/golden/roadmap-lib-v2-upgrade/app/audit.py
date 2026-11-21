"""Audit a stock file for bad rows."""
from recordio import open_reader, Schema, ParseError

SCHEMA = Schema.of(id=str, qty=str)


def audit_file(path):
    """Return a list of problems such as 'row 3: missing field'.

    A structurally broken file (wrong number of fields in a row) yields exactly one
    problem, for the first broken row, and nothing else.
    """
    try:
        with open_reader(path, strip=True) as reader:
            rows = [record.as_dict() for record in reader]
    except ParseError as err:
        return ["row %d: %s" % (err.line - 1, err.message)]
    problems = []
    for number, row in enumerate(rows, start=1):
        clean = SCHEMA.apply(row)
        if not clean["id"] or not clean["qty"]:
            problems.append("row %d: missing field" % number)
    return problems
