# Sample: LicenseManager.class

Received from the IR team together with a vendor SDK jar we cannot match to any published
release. The class is `com.acme.lic.LicenseManager` (class file version for Java 17), no
source, no debug information worth the name.

Files in this directory:

| File | What it is |
| --- | --- |
| `com/acme/lic/LicenseManager.class` | The sample. `java -cp . com.acme.lic.LicenseManager <device-id> <licence-key>` prints `LICENSE OK` or `REJECTED`. |
| `javap.txt` | Disassembly of the sample, produced with `java -m jdk.jdeps/com.sun.tools.javap.Main -c -p -constants LicenseManager.class` on the machine that received it. Use it when the JDK tools are not available on yours. |
| `check.py` | Report acceptance check from the triage workflow. It validates `report/analysis.md` against the required line format and the reviewer's reference results. It is not part of the sample. |

Open questions for whoever picks this up:

- The strings in the constant pool are Base64 blobs, not text. The class decrypts them at run
  time with a small XOR routine.
- The device id we care about is `7f3a-9c21-5e04` (a unit that was bricked after a licence
  server migration). We need the key that unit will accept.
- The constant pool mentions three licence tiers. It is not clear from the listing which tiers
  this build actually lets through.

Deliverable: `report/analysis.md`, checked with `python3 check.py`.
