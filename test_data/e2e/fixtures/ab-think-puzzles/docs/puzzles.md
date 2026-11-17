# Classic insight puzzles - machine-checkable solutions

Two timeless planning puzzles. Both have unique optimal answers, and the gate
(`checks.py`) simulates your plans step by step - constraints included.

## Puzzle 1 - the river crossing

Farmer, wolf, goat and cabbage are on the left bank. The farmer crosses the river with a
boat that carries him plus **at most one item**. If the farmer leaves the wolf alone with
the goat, the wolf eats the goat; if he leaves the goat alone with the cabbage, the goat
eats the cabbage. Get all three across in the **minimal number of crossings (7)**.

Deliverable: `plan/wolf.json` - a JSON array with exactly 7 strings, one per crossing
(alternating left->right, right->left, ...): the item the farmer carries on that
crossing, or `""` when he crosses alone.

## Puzzle 2 - the bridge and the torch

Four people must cross a night bridge, at most **two at a time**, with one torch that
every crossing needs (someone must always bring it back). Crossing times: person `1`
takes 1 minute, `2` takes 2, `5` takes 5, `10` takes 10; a pair moves at the slower
pace. The optimal total is **exactly 17 minutes**.

Deliverable: `plan/bridge.json` - a JSON array of crossings, each an array of the person
ids crossing together (1 or 2 entries), in order.

`python3 checks.py` must exit 0 (it simulates both plans and enforces every constraint
plus the optimal totals). Do not modify the docs or the gate.
