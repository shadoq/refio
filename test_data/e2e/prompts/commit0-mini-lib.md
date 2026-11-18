This repository is `tinylib`, a small dependency-free Python library. The package layout, the public function names and the full documentation (as docstrings) are in place, but every function body is `raise NotImplementedError`. The test suite in `tests/` is complete.

Implement the library so that the whole suite passes: `python -m unittest discover -s tests`. The docstrings in `tinylib/*.py` are the specification, the tests pin down the edge cases.

Do not change anything under `tests/`, and keep the public names and signatures. Work through the five modules one by one; run the suite after each module so you always know where you stand.
