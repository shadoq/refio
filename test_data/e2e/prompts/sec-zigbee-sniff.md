You are doing an offline review of a ZigBee sniffer export (a stand-in
project directory with two ndjson capture files under `captures/`).

Count what the two captures hold and write `report/zigbee.txt` with
exactly these five `key = value` lines (no quotes, no markdown, no extra
keys, exactly one line per key):

- `frames_ch11 = <n>` - frames in `captures/channel11.ndjson`
- `frames_ch15 = <n>` - frames in `captures/channel15.ndjson`
- `pan_ch11 = <pan>` - the PAN id of the channel 11 network (as `0x....`)
- `pan_ch15 = <pan>` - the PAN id of the channel 15 network
- `rejoins_total = <n>` - rejoin requests across BOTH captures (command
  frames with cluster_id 0x0034 addressed to the 0xfffd join address)

These are plain JSON-lines files: every line is a JSON object, there is
no header line. Verify with `python3 check.py` and iterate until it
passes. `check.py`, `README.md` and both captures must stay
byte-identical; only `report/zigbee.txt` may be created.
