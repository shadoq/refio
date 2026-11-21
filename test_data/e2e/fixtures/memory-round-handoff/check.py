"""Acceptance check. Run: python check.py"""
import csv
import hashlib
import os
import random
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)


def gain(c):
    return round(1 + (int(hashlib.sha256(f"harbor-gain-{c}".encode()).hexdigest()[:4], 16) % 400) / 100, 2)


def offset(c):
    return int(hashlib.sha256(f"harbor-off-{c}".encode()).hexdigest()[:4], 16) % 50 - 25


def main():
    problems = []
    notes = os.path.join(ROOT, "NOTES.md")
    text = open(notes, encoding="utf-8").read() if os.path.isfile(notes) else ""
    if not text.strip():
        problems.append("NOTES.md is missing or empty")
    for c in range(6):
        if f"ch{c}" not in text or f"{gain(c):.2f}" not in text:
            problems.append(f"NOTES.md does not record the calibration of channel {c}")

    try:
        from calibrate import convert
        rnd = random.Random(11)
        for _ in range(60):
            c, raw = rnd.randint(0, 5), rnd.randint(0, 4095)
            want = raw * gain(c) + offset(c)
            if abs(convert(c, raw) - want) > 0.005:
                problems.append(f"convert({c}, {raw}) is wrong")
                break
    except Exception as exc:
        problems.append(f"convert failed: {type(exc).__name__}: {exc}")

    out = os.path.join(ROOT, "out.csv")
    if os.path.exists(out):
        os.remove(out)
    run = subprocess.run([sys.executable, os.path.join(ROOT, "report.py")], cwd=ROOT, capture_output=True, text=True)
    if run.returncode != 0 or not os.path.isfile(out):
        problems.append("report.py did not produce out.csv")
    else:
        with open(os.path.join(ROOT, "samples.csv"), encoding="utf-8") as fh:
            samples = list(csv.DictReader(fh))
        with open(out, encoding="utf-8", newline="") as fh:
            got = fh.read().splitlines()
        want = ["channel,raw,value"] + [
            f"{s['channel']},{s['raw']},{int(s['raw']) * gain(int(s['channel'])) + offset(int(s['channel'])):.2f}"
            for s in samples
        ]
        if got != want:
            problems.append("out.csv does not match the expected calibrated values")

    for p in problems:
        print("FAIL: " + p)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
