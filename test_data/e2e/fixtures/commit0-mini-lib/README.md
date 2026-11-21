# tinylib

A small dependency-free Python library with five independent modules.

- `tinylib.semver` - parse and compare semantic versions, check version ranges
- `tinylib.globmatch` - match slash separated paths against glob patterns
- `tinylib.ini` - parse INI-like configuration text with interpolation
- `tinylib.table` - plain text tables
- `tinylib.duration` - parse and format durations such as `1h30m`

Every module has a documented public API. The behaviour is specified by the docstrings and by the test suite in `tests/`.

Run the tests with `python -m unittest discover -s tests`.
