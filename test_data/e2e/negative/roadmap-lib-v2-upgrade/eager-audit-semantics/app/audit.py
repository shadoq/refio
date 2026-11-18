"""Audit a stock file for bad rows."""
from recordio import open_reader, ParseError


def audit_file(path):
    """Return a list of problems such as 'row 3: missing field'."""
    problems = []
    try:
        with open_reader(path, strip=True) as reader:
            for record in reader:
                if not record["id"] or not record["qty"]:
                    problems.append("row %d: missing field" % (record.line - 1))
    except ParseError as err:
        problems.append("row %d: %s" % (err.line - 1, err.message))
    return problems
