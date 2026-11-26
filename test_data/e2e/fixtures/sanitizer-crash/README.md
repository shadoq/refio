# textlib

The C++17 library behind the summary formatter of the batch job. It reads the small text
files the corpus job produces and prints a report: the section header in upper case, the
number of rows, the scores and the weighted total.

Input format: one `name score` row per line, blank lines are ignored, a line that starts
with `#` is a section header. The header is optional, and the final newline may be missing
when a file was truncated on pickup.

## Layout

- `src/` - the library. `textlib.h` documents every function.
- `tests/run_tests.cpp` - the test driver.
- `tests/cases/` - one `<name>.txt` input per case with its `<name>.expected` output.
- `reports/` - sanitizer output from the CI corpus job, one file per crashing input.

## Build and test

    make check

builds with `g++ -std=c++17 -fsanitize=address,undefined -g -O1` and runs the driver on
every case. It exits 0 only when each case prints its expected output; a sanitizer finding
aborts the run. `make all` only builds, `make clean` removes `build/`.
