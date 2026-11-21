"""recordio 2.x - small delimited-record reader and writer."""

MISSING = object()


class ParseError(Exception):
    def __init__(self, message, line=None):
        super().__init__(message if line is None else "line %d: %s" % (line, message))
        self.message = message
        self.line = line


def coerce(value, kind, *, default=MISSING):
    try:
        return kind(value)
    except (ValueError, TypeError):
        if default is MISSING:
            raise ParseError("cannot convert %r to %s" % (value, kind.__name__))
        return default


class Schema:
    def __init__(self, fields):
        self.fields = dict(fields)

    @classmethod
    def of(cls, **fields):
        return cls(fields)

    def apply(self, raw, line=None):
        out = dict(raw)
        for name, kind in self.fields.items():
            if name not in raw:
                raise ParseError("missing field %s" % name, line)
            try:
                out[name] = coerce(raw[name], kind)
            except ParseError as err:
                raise ParseError("%s: %s" % (name, err.message), line)
        return out


class Record:
    def __init__(self, values, line):
        self._values = dict(values)
        self.line = line

    def __getitem__(self, name):
        return self._values[name]

    def get(self, name, default=None):
        return self._values.get(name, default)

    def as_dict(self):
        return dict(self._values)


class Reader:
    def __init__(self, path, sep, strip, schema):
        self._path = path
        self._sep = sep
        self._strip = strip
        self._schema = schema
        self._fh = None

    def __enter__(self):
        self._fh = open(self._path, encoding="utf-8", newline="")
        return self

    def __exit__(self, *exc):
        self._fh.close()
        return False

    def __iter__(self):
        header_line = self._fh.readline()
        if not header_line:
            raise ParseError("missing header", 1)
        header = [h.strip() for h in header_line.rstrip("\r\n").split(self._sep)]
        for number, text in enumerate(self._fh, start=2):
            text = text.rstrip("\r\n")
            if not text.strip():
                continue
            cells = text.split(self._sep)
            if self._strip:
                cells = [c.strip() for c in cells]
            if len(cells) != len(header):
                raise ParseError("expected %d fields, got %d" % (len(header), len(cells)), number)
            values = dict(zip(header, cells))
            if self._schema is not None:
                values = self._schema.apply(values, number)
            yield Record(values, number)


def open_reader(path, *, sep=",", strip=False, schema=None):
    return Reader(path, sep, strip, schema)


class Writer:
    def __init__(self, path, columns, sep):
        self._path = path
        self._columns = list(columns)
        self._sep = sep
        self._fh = None

    def __enter__(self):
        self._fh = open(self._path, "w", encoding="utf-8", newline="")
        self._fh.write(self._sep.join(self._columns) + "\n")
        return self

    def __exit__(self, *exc):
        self._fh.close()
        return False

    def write(self, mapping):
        extra = [k for k in mapping if k not in self._columns]
        if extra:
            raise ParseError("unexpected column %s" % extra[0])
        self._fh.write(self._sep.join(str(mapping.get(c, "")) for c in self._columns) + "\n")

    def write_all(self, rows):
        for row in rows:
            self.write(row)


def open_writer(path, *, columns, sep=","):
    return Writer(path, columns, sep)
