# Instructions

Implement `IntervalIndex` in `src/interval-index.ts`: a collection of labelled closed intervals that can be searched for overlaps.

## Intervals

An interval is `{ id: string, start: number, end: number }` and covers every number `x` with `start <= x <= end`, both ends included. A zero-length interval (`start === end`) is valid and covers exactly one point.

## Methods

- `add(id, start, end)` stores an interval. It throws `RangeError` when `start` or `end` is not a finite number or when `start > end`. It throws `Error` (not `RangeError`) when `id` is already in the index. A rejected call changes nothing.
- `remove(id)` removes the interval with that id and returns `true`, or returns `false` when there is none. A removed id can be added again.
- `size` (getter) is the number of stored intervals.
- `queryPoint(x)` returns every stored interval that covers `x`.
- `queryRange(lo, hi)` returns every stored interval that shares at least one point with the closed range `[lo, hi]`. Touching counts: an interval ending exactly at `lo`, or starting exactly at `hi`, overlaps. It throws `RangeError` when `lo > hi` or either is not finite. `queryRange(x, x)` is the same as `queryPoint(x)`.

## Results

- Both queries return a new array of new `{ id, start, end }` objects, so changing a result never changes the index.
- The array is sorted by `start` ascending, then by `id` in plain code unit order (the order of the `<` operator on strings, not locale order: `"B"` comes before `"a"`).
- Several intervals with identical bounds but different ids are all kept and all reported.
