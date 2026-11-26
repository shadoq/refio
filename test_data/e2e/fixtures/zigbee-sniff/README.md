# ZigBee sniffer export: building-2 sensors

Two days of sniffer output from the building-2 sensor mesh, one file per
channel. Each line is one frame as the sniffer decoded it: ts, src, dst,
profile_id, cluster_id, frame_type, pan_id. Rejoin requests show up as
command frames with cluster 0x0034 addressed to the 0xfffd join address.

- `captures/channel11.ndjson` - the 2.4 GHz channel 11 capture.
- `captures/channel15.ndjson` - the 2.4 GHz channel 15 capture.
- `check.py` - acceptance gate for this analysis (run `python3 check.py`).

Write your numbers to `report/zigbee.txt`. The files are plain JSON lines:
there is no header line to skip.
