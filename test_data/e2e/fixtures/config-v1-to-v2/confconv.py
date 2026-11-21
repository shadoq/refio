"""Convert v1 (INI-like) config text to the v2 (YAML-like) format. See SPEC.md."""

DEFAULTS = {
    "server": [("host", "localhost"), ("port", "8080"), ("debug", "false")],
    "db": [("pool", "5"), ("url", "sqlite://memory")],
    "log": [("level", "info")],
}


def convert(text):
    raise NotImplementedError("convert is not written yet")
