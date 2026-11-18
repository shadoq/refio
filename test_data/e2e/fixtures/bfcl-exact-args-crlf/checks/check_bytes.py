"""Checks the rounding fix and that nothing else in faktura.py was touched, byte for byte.

Run from the project root: python checks/check_bytes.py
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
baseline = json.loads((Path(__file__).resolve().parent / "baseline.json").read_text(encoding="utf-8"))
data = (ROOT / "faktura.py").read_bytes()

problems = []
if data.startswith(b"\xef\xbb\xbf"):
    problems.append("a UTF-8 byte order mark was added")
try:
    data.decode("utf-8")
except UnicodeDecodeError:
    problems.append("the file is no longer valid UTF-8")
if data.count(b"\n") != data.count(b"\r\n"):
    problems.append("there are line endings that are not CRLF")
if b"\r\r" in data:
    problems.append("doubled carriage returns")

lines = data.split(b"\r\n")
digests = [hashlib.sha1(line).hexdigest() for line in lines]
old = baseline["lines"]
start, end = baseline["region_start"], baseline["region_end"]
tail = len(old) - end
if digests[:start] != old[:start]:
    problems.append("lines before the money() function were changed")
if tail > 0 and digests[-tail:] != old[-tail:]:
    problems.append("lines after the money() function were changed")

if problems:
    print("BYTE CHECK FAILED:")
    for problem in problems:
        print("  " + problem)
    sys.exit(1)

result = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."], cwd=str(ROOT))
sys.exit(result.returncode)
