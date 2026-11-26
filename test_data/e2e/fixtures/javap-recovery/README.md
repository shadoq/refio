# Sample: InterestCalculator.class

Pulled from a vendor settlement jar (release unknown, the jar's manifest was stripped) after
the source archive that matched it was lost in a repository migration. The class is Java 17
(class file version 61), default package, no debug information beyond line numbers. The
routine computes interest for fixed-term deposits: amounts are in cents, the tier table and
the day-count handling live inside.

Files in this directory:

| File | What it is |
| --- | --- |
| `InterestCalculator.class` | The sample, as extracted from the jar. `java -cp . InterestCalculator <amount-cents> <days> <year>` prints one result line; the main also accepts several triples at once. |
| `javap.txt` | Disassembly of the sample, produced with `java -m jdk.jdeps/com.sun.tools.javap.Main -c -p -constants InterestCalculator.class` on the machine that received it. Use it when the JDK tools are not available on yours. |
| `decompiled/InterestCalculator.java` | Output of the decompiler we tried first. It compiles, but this decompiler build is unreliable: we already spotted signs of an inverted comparison at a tier boundary, a division that looks suspiciously rounded, and a ternary that does not match the operand order in the listing. Treat it as a hint only. |
| `check.py` | Acceptance check from the recovery workflow. It validates the report lines and compiles and diffs the recovered source against the shipped class. It is not part of the sample. |

What we need: a `src/InterestCalculator.java` that we can rebuild and diff against the class
on our regression inputs, so the settlement calculations keep their exact current behaviour
(the auditors insist on bit-exact continuity, including the parts that look like bugs), plus a
short report of where the decompiler output is wrong.

Deliverables: `report/recovery.md` and `src/InterestCalculator.java`, checked with
`python3 check.py`.
