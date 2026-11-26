`InterestCalculator.class` is the only surviving class of a vendor interest routine (Java 17,
no source; see `README.md`). Two renderings are shipped next to it:

- `javap.txt`: the disassembly, produced with
  `java -m jdk.jdeps/com.sun.tools.javap.Main -c -p -constants InterestCalculator.class`.
  The listing is authoritative: recover the behaviour from it. If a decompiler on your
  machine shows something different, trust the listing.
- `decompiled/InterestCalculator.java`: the output of a decompiler that is known to get
  three things wrong here: one branch condition came out inverted at a tier boundary
  (strict versus non-strict comparison), one integer division was "fixed" into a
  floating-point rounding, and one ternary expression lost the parentheses that the
  bytecode's operand order implies. It is a hint, not the answer.

Two deliverables:

1. `report/recovery.md` with exactly three lines of the form (plain text, no table, no
   bullet, nothing else on the line):

```
mistake = <line number in decompiled/InterestCalculator.java> | <what the bytecode really does>
```

   One line per mistake: the line number (1-based, from the top of
   `decompiled/InterestCalculator.java`) where that mistake sits, then a bar, then a short
   explanation in your own words of what the bytecode really does there.

2. `src/InterestCalculator.java`: a reconstruction of the source that reproduces the class's
   behaviour EXACTLY, bugs included. Do not fix the integer division, the tier boundary or
   anything else the class does; the goal is a faithful recovery, not a better program. The
   class reads amount in cents, days and year as command line arguments in groups of three
   and prints one line per group with amount, days, year, leap, tier, rate and interest.

`check.py` (run it with `python3` in the project directory) reads your report, compiles your
source with

```
java -m jdk.compiler/com.sun.tools.javac.Main -d <temp dir> src/InterestCalculator.java
```

(a JDK 17 or newer on PATH is needed for this), then runs BOTH the shipped class and your
recompiled source on a fixed grid of inputs (tier boundaries, leap days, zero, negative and
large values) and compares the output byte for byte. Do not modify `InterestCalculator.class`,
`javap.txt`, `decompiled/InterestCalculator.java`, `README.md` or `check.py`.
