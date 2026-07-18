`python3 -m unittest test_slugify` fails. The `slugify` function in `src/slugify.py` does
not match its own docstring: the result must be lowercase and must not start or end with a
dash. Fix `src/slugify.py` so all tests pass. Do not edit `test_slugify.py`.
