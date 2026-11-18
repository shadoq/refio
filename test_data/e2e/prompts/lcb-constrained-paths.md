Implement `shortestPath(n, edges, src, dst, k)` in `src/paths.js` (Node, no dependencies). The signature and the contract are in the comment above the stub.

Problem: a directed road network has `n` junctions numbered `0..n-1`. Each road is `[from, to, cost, toll]` with `cost >= 0` and `toll` equal to 1 (or `true`) for a toll road and 0 (or `false`) otherwise. Roads may be parallel and may be self loops. Find the minimum total cost of a route from `src` to `dst` that uses at most `k` toll roads, counting every toll road each time it is driven. Return `-1` when no such route exists, and `0` when `src === dst`.

Throw a `RangeError` for: `n` not a positive integer, `src` or `dst` outside `0..n-1`, `k` not a non-negative integer, a road endpoint outside `0..n-1`, or a road cost that is negative or not finite.

Inputs can be large: up to about 100000 junctions and 400000 roads, and `k` may be any non-negative integer, including values like 1000000000. The tests run big instances with a time limit, so enumerating routes will not finish.

The tests in `tests/paths.test.js` are the acceptance check: `node --test tests/paths.test.js` must exit 0. Do not edit anything under `tests/`.
