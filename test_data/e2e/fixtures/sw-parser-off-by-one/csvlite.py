"""csvlite - the minimal row reader behind the nightly billing export.

The export job feeds us one physical row at a time and only needs the RFC 4180
subset we actually emit: comma-separated fields, optional double-quoted fields
and "" escapes inside quoted fields. Multi-line fields are out of scope.

Examples:
    split_row('a,b,c')       -> ['a', 'b', 'c']
    split_row('"x,y",z')     -> ['x,y', 'z']
    split_row('"a""b",c')    -> ['a"b', 'c']
    split_row('a,,c')        -> ['a', '', 'c']
"""

DEFAULT_SEPARATOR = ","


def split_row(line, separator=DEFAULT_SEPARATOR):
    """Split one physical row into its list of fields."""
    fields = []
    field = []
    in_quotes = False
    i = 0
    while i < len(line):
        ch = line[i]
        if in_quotes:
            if ch == '"':
                # A doubled quote inside a quoted field is an escaped quote.
                if i + 1 < len(line) and line[i + 1] == '"':
                    field.append('"')
                    i += 1
                else:
                    in_quotes = False
            else:
                field.append(ch)
        elif ch == '"':
            in_quotes = True
        elif ch == separator:
            fields.append("".join(field))
            field = []
        else:
            field.append(ch)
        i += 1
    # Only flush a non-empty buffer, so plain rows do not grow a phantom
    # trailing field.
    if field:
        fields.append("".join(field))
    return fields


def parse(text, separator=DEFAULT_SEPARATOR):
    """Parse a whole export into rows, skipping blank physical lines."""
    rows = []
    for line in text.splitlines():
        if line:
            rows.append(split_row(line, separator))
    return rows


def pad_row(row, width, fill=""):
    """Right-pad a row to `width` fields (used when joining partial exports)."""
    return list(row) + [fill] * (width - len(row))
