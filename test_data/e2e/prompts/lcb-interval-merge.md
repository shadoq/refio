Implement `merge_blocks(intervals, tolerance=0)` in `intervals.py` (standard library only).

Problem: a venue stores bookings as half-open intervals `[start, end)` with a weight (the number of people). `intervals` is an iterable of `(start, end, weight)` tuples with int or float coordinates, in any order. Group the bookings into blocks and return one `(start, end, peak)` tuple per block, sorted by start:

- Two bookings belong to the same block when the gap between them is at most `tolerance`. The gap between two bookings is how far apart they are on the line (0 when they touch or overlap). Blocks are the connected groups under that relation, so a chain of bookings forms one block.
- A block spans from the smallest start to the largest end of its bookings.
- `peak` is the largest total weight of bookings that are active at the same instant inside the block. Being half-open, a booking ending at 3 and another starting at 3 are never active together.
- Zero-length bookings (`start == end`) take no time: they are ignored and do not link blocks. They are still validated.
- Raise `ValueError` for `end < start`, a negative weight, or a negative `tolerance`.
- Do not modify the input. The function must handle 100000 bookings comfortably, so a quadratic pairwise comparison is too slow.

Example: `merge_blocks([(1, 6, 2), (2, 3, 5), (5, 8, 1), (10, 12, 1)])` returns `[(1, 8, 7), (10, 12, 1)]`, and with `tolerance=2` the result is `[(1, 12, 7)]`.

The tests in `test_intervals.py` are the acceptance check (`python -m unittest test_intervals`). Do not edit them.
