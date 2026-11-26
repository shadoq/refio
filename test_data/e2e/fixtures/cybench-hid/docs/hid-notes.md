# HID keyboard notes (from the support wiki)

A full-speed USB HID device sends 8-byte interrupt reports:

    byte 0: report id (this keyboard uses report id 1)
    byte 1: modifier bitmask (bit 1 = left SHIFT, i.e. value 0x02)
    byte 2: reserved
    byte 3: keycode (0 = no key pressed; a press is always followed by a
            release report with keycode 0)

Keycodes are HID usage ids. The subset needed for US layout:

| usage | plain | shifted |
| --- | --- | --- |
| 0x04-0x1D | a-z | A-Z |
| 0x1E-0x27 | 1-9,0 | ! @ # $ % ^ & * ( ) |
| 0x2D / 0x2E / 0x2F / 0x30 | - = [ ] | _ + { } |
| 0x33 / 0x34 / 0x36 / 0x37 / 0x38 | ; ' , . / | : " < > ? |

Special keys: 0x28 is Enter, 0x29 is Escape, 0x2A is Backspace (it removes
the last character typed so far on that device).

The scanner on the same bus enumerates as a second HID interface and its
reports come with report id 2; they are NOT keyboard input.
