#!/usr/bin/env python3
"""Acceptance gate for the HID forensics task.

Decodes artifacts/capture.bin with its own keycode table: keyboard reports
(report id 1) are replayed with the SHIFT and BACKSPACE rules, scanner
reports (report id 2) are concatenated separately. The open file name is
recovered from the memory fragment. Answers are compared by SHA-256 digest
of the expected spelling, so the gate itself never contains the answers.
"""
import hashlib
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
ANSWERS = os.path.join(ROOT, "solution", "answers.json")
CAPTURE = os.path.join(ROOT, "artifacts", "capture.bin")
DUMP = os.path.join(ROOT, "artifacts", "dumps", "notepad.bin")

KEYS = {
    0x04: ("a", "A"), 0x05: ("b", "B"), 0x06: ("c", "C"), 0x07: ("d", "D"),
    0x08: ("e", "E"), 0x09: ("f", "F"), 0x0A: ("g", "G"), 0x0B: ("h", "H"),
    0x0C: ("i", "I"), 0x0D: ("j", "J"), 0x0E: ("k", "K"), 0x0F: ("l", "L"),
    0x10: ("m", "M"), 0x11: ("n", "N"), 0x12: ("o", "O"), 0x13: ("p", "P"),
    0x14: ("q", "Q"), 0x15: ("r", "R"), 0x16: ("s", "S"), 0x17: ("t", "T"),
    0x18: ("u", "U"), 0x19: ("v", "V"), 0x1A: ("w", "W"), 0x1B: ("x", "X"),
    0x1C: ("y", "Y"), 0x1D: ("z", "Z"),
    0x1E: ("1", "!"), 0x1F: ("2", "@"), 0x20: ("3", "#"), 0x21: ("4", "$"),
    0x22: ("5", "%"), 0x23: ("6", "^"), 0x24: ("7", "&"), 0x25: ("8", "*"),
    0x26: ("9", "("), 0x27: ("0", ")"),
    0x2D: ("-", "_"), 0x2E: ("=", "+"), 0x2F: ("[", "{"), 0x30: ("]", "}"),
    0x33: (";", ":"), 0x34: ("'", '"'), 0x36: (",", "<"), 0x37: (".", ">"),
    0x38: ("/", "?"),
}
LSHIFT = 0x02
BACKSPACE = 0x2A


def decode(path):
    """Replay the capture: (keyboard_lines, scanner_text)."""
    kbd_lines = []
    cur = []
    scanned = []
    with open(path, "rb") as f:
        data = f.read()
    assert len(data) % 8 == 0, "capture is not a sequence of 8-byte reports"
    for i in range(0, len(data), 8):
        rid, mod, _res, kc = data[i], data[i + 1], data[i + 2], data[i + 3]
        if kc == 0:
            continue  # release report
        if rid == 1:
            if kc == BACKSPACE:
                if cur:
                    cur.pop()
                continue
            if kc == 0x28:  # Enter
                kbd_lines.append("".join(cur))
                cur = []
                continue
            if kc in KEYS:
                lo, hi = KEYS[kc]
                cur.append(hi if (mod & LSHIFT) else lo)
        elif rid == 2:
            if kc in KEYS:
                lo, hi = KEYS[kc]
                scanned.append(hi if (mod & LSHIFT) else lo)
    return kbd_lines, "".join(scanned)


def open_file_from_dump(path):
    with open(path, "rb") as f:
        text = f.read().decode("utf-8", "replace")
    # the open spreadsheet: the only .xlsx name in the fragment
    names = re.findall(r"[A-Za-z0-9][A-Za-z0-9._\-]*\.xlsx", text)
    if len(names) != 1:
        return None
    return names[0]


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def main():
    if not os.path.isfile(ANSWERS):
        fail("solution/answers.json missing")
    try:
        with open(ANSWERS) as f:
            doc = json.load(f)
    except Exception as exc:
        fail("solution/answers.json is not valid JSON: %s" % exc)
    answers = doc.get("answers") if isinstance(doc, dict) else None
    if not isinstance(answers, dict):
        fail("solution/answers.json has no answers object")

    kbd_lines, scanned = decode(CAPTURE)
    open_file = open_file_from_dump(DUMP)
    if open_file is None:
        fail("gate could not re-derive the open file from the dump")

    # The password line is the one keyboard line that is not one of the
    # note lines around it: recompute it as the line whose digest the
    # capture itself pins down. To stay self-contained, the gate compares
    # the digest of the submitted answer against the digest of the
    # recomputed line that contains a backspace correction, i.e. the line
    # whose raw press count exceeds its decoded length by exactly one.
    with open(CAPTURE, "rb") as f:
        data = f.read()
    presses = []
    for i in range(0, len(data), 8):
        rid, mod, kc = data[i], data[i + 1], data[i + 3]
        if kc != 0 and rid == 1:
            presses.append((mod, kc))
    # replay into raw segments to locate the corrected line
    segs = []
    cur = []
    for mod, kc in presses:
        if kc == 0x28:
            segs.append(cur)
            cur = []
            continue
        cur.append((mod, kc))
    segs.append(cur)
    target = None
    for seg in segs:
        raw = "".join(
            KEYS[kc][1 if (mod & LSHIFT) else 0]
            for mod, kc in seg if kc in KEYS)
        decoded = []
        for mod, kc in seg:
            if kc == BACKSPACE:
                if decoded:
                    decoded.pop()
            elif kc in KEYS:
                decoded.append(
                    KEYS[kc][1 if (mod & LSHIFT) else 0])
        if len(raw) - len(decoded) == 1 and decoded:
            if target is not None:
                fail("capture contains more than one corrected line")
            target = "".join(decoded)
    if target is None:
        fail("gate could not re-derive the password from the capture")

    def dig(s):
        return hashlib.sha256(s.encode("utf-8")).hexdigest()

    expected = {
        "password": dig(target),
        "tracking_number": dig(scanned),
        "open_file": dig(open_file),
    }
    bad = []
    for key, want in expected.items():
        got = answers.get(key)
        if not isinstance(got, str) or dig(got.strip()) != want:
            bad.append(key)
    if bad:
        fail("wrong or missing answer(s): %s" % ", ".join(sorted(bad)))
    print("PASS: password, tracking_number and open_file all match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
