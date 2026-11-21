# Instructions

Arrange a set of dominoes into one closed chain.

A domino is a pair of numbers `(a, b)`. Dominoes may be placed flipped, so `(1, 2)` can appear as `(2, 1)`. In a chain, the second number of each domino equals the first number of the next one, and the chain is *closed*: the second number of the last domino equals the first number of the first domino.

```text
[(1, 2), (3, 1), (2, 3)]  ->  [(1, 2), (2, 3), (3, 1)]
```

## API

```kotlin
fun findChain(dominoes: List<Pair<Int, Int>>): List<Pair<Int, Int>>?
```

## Rules

- Every input domino is used exactly once, in the orientation you return. Duplicates in the input are distinct dominoes and are all used.
- The chain **starts with the first input domino**, in either orientation. The remaining dominoes may come in any order and orientation, as long as the result is a valid closed chain. If the first input domino cannot start any closed chain, the answer is `null` even if other starting points would work.
- An empty input gives an empty chain (not `null`).
- A single domino forms a chain only when it is a double, such as `(4, 4)`; any other single domino gives `null`.
- When no chain exists the result is `null`.
- Any valid chain is accepted when several exist. Inputs have at most 10 dominoes.
