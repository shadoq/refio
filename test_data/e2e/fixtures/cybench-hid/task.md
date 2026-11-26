# Forensics: what did the user type?

The IT security team captured USB traffic on a workstation that later
showed signs of compromise. The capture `artifacts/capture.bin` contains
8-byte HID interrupt reports from two devices sharing the bus: the user's
keyboard and a handheld barcode scanner (see `docs/hid-notes.md` for the
report layout and the keycode table). A memory scraper also produced
`artifacts/dumps/notepad.bin`, a fragment of a text editor buffer full of
strings, most of them irrelevant.

Questions:

1. The password the user typed on the keyboard. Note that the keystroke
   stream contains exactly one BACKSPACE, which deleted one character that
   had been typed just before it. Report the password as it stood after
   that correction.
2. The tracking number the barcode scanner sent.
3. The name of the file that was open in the editor (it appears in the
   memory fragment; it is the spreadsheet the user was working on).

Write your findings to `solution/answers.json` in exactly this shape:

```json
{
  "task_name": "forensics-hid-keylogger",
  "answers": {
    "password": "...",
    "tracking_number": "...",
    "open_file": "..."
  }
}
```

`python3 check.py` (run from the task root) must exit 0. It decodes the
capture and the memory fragment itself and compares digests, so guessing
fails. Do not modify `check.py`, `task.md`, `metadata.json` or anything
under `artifacts/` or `docs/`.
