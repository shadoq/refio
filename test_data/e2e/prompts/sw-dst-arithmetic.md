## Weekly meetings drift by an hour after the DST switch

### Symptom

Since the Europe/Warsaw spring-forward weekend the confirmation emails for the
weekly room booking show the wrong slot: a meeting created as 09:30 on Monday
2024-03-25 comes back as 10:30 on 2024-04-01 (and after fall back it slides
the other way, 09:30 -> 08:30). Meetings that do not cross a transition are
fine, which is why the no-transition cases never caught it.

### How to reproduce

    python3 - <<'PY'
    from datetime import datetime
    from zoneinfo import ZoneInfo
    from scheduler import shift_meeting

    WARSAW = ZoneInfo("Europe/Warsaw")
    print(shift_meeting(datetime(2024, 3, 25, 9, 30, tzinfo=WARSAW), 7))
    # 2024-04-01 10:30+02:00   (expected 2024-04-01 09:30+02:00)
    PY

`python3 checks.py` currently fails on the two transition replays.

### Root cause

`shift_meeting` in `scheduler.py` computes the shift on a naive copy of the
local time and then stamps the ORIGINAL UTC offset back on with a fixed
`timezone(offset)`. Across a CET/CEST transition the offset of the target day
differs, so the wall clock moves by the offset difference instead of staying
put.

### Your task

1. Reproduce the drift with the snippet above and confirm the root cause in
   `shift_meeting`.
2. Fix `scheduler.py` in place so a meeting moves by whole days on the local
   wall clock: same slot, N days later, with the UTC offset recomputed for
   the target day in Europe/Warsaw. Naive input keeps being read as local
   wall time, aware input keeps being converted first, and `weekly_slots`
   and `describe` line up with the fixed shift.
3. Run the gate: `python3 checks.py` - it replays both 2024 transitions, the
   naive and aware input paths and the no-transition regressions, and must
   pass.

Do not modify `checks.py` or the README: the fix belongs in `scheduler.py`.
