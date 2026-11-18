We have a small bench tool for the HB-6 sensor board and it needs to be finished. This is a longer job, so work in steps and leave yourself notes.

1. Find out the calibration of the board. It is not documented anywhere; the board tool `probe.py` reports it. Record what you find for each of the six channels in `NOTES.md` (one line per channel: `chN`, gain, offset).
2. Implement `convert(channel, raw)` in `calibrate.py` as described in `README.md`, using the calibration from your notes.
3. Implement `report.py` so that it turns `samples.csv` into `out.csv` as described in the README.

`python check.py` is the acceptance check. Do not edit `probe.py`, `check.py` or `samples.csv`. Do not guess the calibration values.
