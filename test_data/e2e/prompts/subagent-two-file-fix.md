Two files under `src/` each contain a boundary (off-by-one) bug. Handle them one at a time,
and let the subagent make the edit - do not fix the files yourself.

1. Use the `refactoring-specialist` subagent to fix `src/discount.py` so the 10 percent
   discount applies when the total is exactly 100 (right now it only applies above 100).

2. Then use the `refactoring-specialist` subagent again to fix `src/shipping.py` so shipping
   is free when the weight is exactly 50 kg (right now it only becomes free above 50 kg).

Do not modify `main.py`.
