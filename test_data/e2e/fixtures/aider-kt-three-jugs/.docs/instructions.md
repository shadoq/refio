# Instructions

Measure an exact volume of water with three jugs of different sizes, using as few moves as possible.

## API

```kotlin
enum class Op { FILL, EMPTY, POUR }

// FILL jug / EMPTY jug use `jug` only; POUR moves water from `jug` into `into`.
data class Move(val op: Op, val jug: Int, val into: Int = -1)

fun measure(capacities: List<Int>, target: Int): List<Move>?
```

`capacities` holds the sizes of the three jugs (index 0, 1, 2). All jugs start empty. The goal is reached as soon as **any** jug holds exactly `target`.

## Moves

- `FILL(i)` fills jug `i` to its capacity from the tap.
- `EMPTY(i)` pours jug `i` out completely.
- `POUR(i, j)` pours jug `i` into jug `j` until `i` is empty or `j` is full, whichever comes first.

A move that changes nothing is not a legal move: filling a full jug, emptying an empty one, or pouring when `i` is empty or `j` is full.

## Result

`measure` returns the shortest sequence of legal moves that reaches the goal. The empty list means the goal already holds at the start (`target` 0). It returns `null` when the target can never be reached.

Several shortest sequences usually exist, and the result must be exactly the one a breadth-first search finds when it expands every state its moves in this fixed order, and keeps the first way it discovers each state:

1. `FILL(0)`, `FILL(1)`, `FILL(2)`
2. `EMPTY(0)`, `EMPTY(1)`, `EMPTY(2)`
3. `POUR(i, j)` for `i` in 0..2, and for each `i`, `j` in 0..2 with `j != i` (so `POUR(0,1)`, `POUR(0,2)`, `POUR(1,0)`, `POUR(1,2)`, `POUR(2,0)`, `POUR(2,1)`)

States are expanded in the order they were discovered (a first-in first-out queue).

## Errors

`IllegalArgumentException` when `capacities` does not hold exactly three values, when any capacity is not positive, or when `target` is negative.
