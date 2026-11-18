# HB-6 bench tools

`probe.py` prints the board calibration. `calibrate.convert(channel, raw)` must return `raw * gain + offset` for the channel.
`report.py` reads `samples.csv` (channel,raw) and writes `out.csv` with the header `channel,raw,value`, one row per sample in the same order, value with exactly two decimals.
`python check.py` is the acceptance check.
