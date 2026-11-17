Two classic insight puzzles with machine-checkable answers are described in
`docs/puzzles.md`: the river crossing (farmer, wolf, goat, cabbage - boat carries at
most one item; nothing may be left unsupervised with its predator/food) and the
bridge-and-torch (four people, times 1/2/5/10, at most two per crossing, torch must
shuttle; the optimal total is exactly 17 minutes).

Your task: solve both and write the plans exactly in the documented format -
`plan/wolf.json` (7 strings, the item carried per crossing, `""` when alone) and
`plan/bridge.json` (array of 1-2-person crossings, in order). `python3 checks.py` must
exit 0 - it simulates both plans step by step and enforces the optimal totals. Do not
modify the docs or the gate.
