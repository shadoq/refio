# Sanitizer crash triage - CI corpus job 48112

Sources: `reports/crash-{1..4}.txt` (AddressSanitizer / UndefinedBehaviorSanitizer), the
frames they name under `src/`, and `tests/cases/` for the expected behaviour of every
input.

## Reports

crash = crash-1 | CWE-125 | line_length | src/lines.cpp
crash = crash-2 | CWE-787 | upcase | src/format.cpp
crash = crash-3 | CWE-190 | weighted_total | src/stats.cpp
crash = crash-4 | CWE-476 | render | src/report.cpp

## Fixes

- `line_length` stops at the end of the text, so a truncated last line is still read.
- `upcase` writes `n` characters, one less than before.
- `weighted_total` checks the multiply and the add and saturates as `textlib.h` promises.
- `render` prints the report without a section line when there is no header.

`make check` passes all six cases under both sanitizers.
