We are moving our service configuration from the old INI-like v1 files to the new YAML-like v2 text format. Write the converter: `convert(text)` in `confconv.py`, following `SPEC.md` exactly (value rules, comment handling, defaults, whitespace and error messages). Comments must survive the conversion.

`python -m unittest test_confconv` is the acceptance check. Do not edit the tests or the spec, and use only the standard library.
