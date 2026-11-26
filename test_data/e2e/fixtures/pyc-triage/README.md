# Sample: loader.pyc (stage 1 of a dropper)

Collected from `%APPDATA%\UpdChk\loader.pyc` on a Windows 10 workstation (case 2025-1117-04)
after the host tripped an EDR alert for a suspicious scheduled task. Renamed `sample.pyc` for
the case tree. The file is CPython 3.12 bytecode (the magic number matches 3.12); no source
or sibling stages were recovered, the dropper body was not found on disk.

Files in this directory:

| File | What it is |
| --- | --- |
| `sample.pyc` | The sample, as collected. |
| `dis.txt` | Disassembly of the whole module, produced with `dis.dis(marshal.loads(data[16:]), depth=None)` on the machine that received the sample. Use it when your local Python is not 3.12. |
| `strings.txt` | The printable runs of 4 or more characters that `strings` would report. |
| `check.py` | IOC acceptance check from the triage workflow. It validates `report/iocs.md` against the required line format and the reviewer's reference results. It is not part of the sample. |

What we know so far (first responder notes, not verified):

- Executing the sample in a VM produced no network traffic and no file writes; it printed one
  status line and exited. The config it builds is never printed on that path.
- The constant pool holds one long Base64 blob and nothing readable beyond generic names, so
  the configuration is encoded.
- The EDR alert mentions a Run key persistence attempt and a document-encryption follow on
  stage, so expect a command and an extension somewhere in the config.

Deliverable: `report/iocs.md`, checked with `python3 check.py`.
