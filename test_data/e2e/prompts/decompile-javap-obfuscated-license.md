`com/acme/lic/LicenseManager.class` is a Java 17 class (package `com.acme.lic`) taken from a
vendor's licensing SDK. The vendor shipped no source. `javap.txt` is its disassembly, produced
with `java -m jdk.jdeps/com.sun.tools.javap.Main -c -p -constants LicenseManager.class`. The
listing is authoritative: do the analysis from it. If a decompiler on your machine shows
something different, trust the listing.

The class decides whether a licence key is valid for a device id. Its strings are encrypted
(XOR with a byte array key, stored as Base64), several constants are computed in the static
initialiser instead of being written as literals, and the tier branches do not all do what
their names suggest.

Recover the algorithm by reading the bytecode and write `report/analysis.md` with exactly these
five lines. Each line is plain text of the form `key = value` (no table, no bullet, nothing
else on the line):

```
key_for_device = <the licence key the program accepts for device id 7f3a-9c21-5e04, exactly as accepted>
xor_key_hex = <the XOR key bytes as contiguous hex, in order>
magic_constant = <the magic constant as a decimal integer>
accepted_class = <the licence class the branch structure really accepts, lower-case tier name>
permutation = <the device permutation as decimal integers separated by commas, no spaces, in index order>
```

The key must be given in the exact form the program accepts (same tag letter, same character
case, same grouping). Hex may be lower or upper case, with or without a leading `0x`. The
permutation may be wrapped in brackets.

The answer must be derived from the artifact, not guessed: the values are not stored anywhere
in readable form. If `java` is available you may confirm your key by running
`java -cp . com.acme.lic.LicenseManager 7f3a-9c21-5e04 <key>` (prints `LICENSE OK` or
`REJECTED`), but running the class only confirms a key, it does not give you one.

When the report is written, run `python3 check.py` in the project directory and fix the report
until it prints `PASS`. Do not modify `README.md`, `javap.txt`, the class file or `check.py`.
