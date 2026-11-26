CI runs the corpus job's inputs through the batch formatter with AddressSanitizer and
UndefinedBehaviorSanitizer. Job 48112 produced four crash reports; they are stored in
`reports/crash-1.txt` to `reports/crash-4.txt` with paths relative to the project root.
Triage the four crashes and fix the library.

1. For each report, read the sanitizer output, follow the frames into `src/` and find the
   line that causes the defect. Decide which weakness it is, by its MITRE CWE identifier
   (`CWE-<number>`), and name the function whose code is the root cause (not the driver or
   the standard library frames, and not the caller that hands over correct data).

2. Write `report/triage.md`. Each crash gets exactly one line in exactly this form, with
   the function name alone and the path relative to the project root:

   ```
   crash = crash-N | CWE-n | <function> | <file>
   ```

   Use the file name of the report for `crash-N` (`crash-1` for `reports/crash-1.txt`).
   Around the lines, explain each root cause in a sentence and describe the fix.

3. Fix the four defects in `src/`. The fixes must keep the documented behaviour
   (`src/textlib.h`, `README.md`) and print the expected output for every input in
   `tests/cases/` - the four inputs that crashed and the two normal ones. A change that
   avoids a crash by dropping or truncating input, or by clamping valid results, is not a
   fix.

`check.py` is the acceptance gate: it compares your four lines with the reviewed triage
(stored as digests; it says how many lines disagree, not which) and then runs `make check` on a fresh build,
which builds with `g++ -std=c++17 -fsanitize=address,undefined -g -O1` and runs the test
driver on every case. All cases must pass. Run it with `python3 check.py` and iterate
until it passes. Build and run the tests yourself with `make check` (through `make`; the
compiler is not called directly). `g++` and `make` are needed on PATH.

Do not modify `check.py`, `Makefile`, `src/textlib.h`, anything under `tests/` or
`reports/`, or `README.md`. Leave no build output behind (`make clean`).
