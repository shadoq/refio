# Instructions

Implement a small reactive spreadsheet engine: input cells that hold values, compute cells derived from other cells, and callbacks that run when a cell's value changes.

## API

`reactive-cells.js` exports `InputCell` and `ComputeCell` (CommonJS, `module.exports = { InputCell, ComputeCell }`).

- `new InputCell(value)` has a `value` getter and `setValue(newValue)`.
- `new ComputeCell(inputs, fn)` takes an array of cells (input or compute cells) and a function. Its `value` is `fn(...inputs.map(c => c.value))`, computed at construction and kept up to date. It has only a `value` getter; it cannot be set.
- Every cell has `addCallback(cb)` and `removeCallback(cb)`. A callback is called with the new value of the cell.

## Updating

`setValue` does nothing when the new value is the same as the current one (compared with `Object.is`, so `NaN` equals `NaN`). Otherwise one update round runs:

1. Every cell that depends, directly or through other compute cells, on the changed input is brought up to date before any callback runs. A compute cell is recomputed only when at least one of its inputs actually changed value during this round, and its function is called at most once per round, even when the cell is reachable through several paths (diamond dependencies).
2. Callbacks run after all cells are up to date, so a callback that reads other cells sees the final values of the round, never an intermediate one.
3. A cell's callbacks run only when the cell's value after the round differs (`Object.is`) from its value before the round. A cell that is recomputed to the same value does not call its callbacks, and neither does a cell that passed through an intermediate value and came back.

A callback runs at most once per cell per round. Callbacks are called in this order: cells in the order in which they were created, and for one cell in the order in which its callbacks were added. An input cell's own callbacks run too, as the first ones, since it was created before its dependents.

## Callbacks

- Adding a function that is already registered on the same cell does nothing; it is still called once.
- `removeCallback` of a function that is not registered does nothing.
- A callback that is removed while a round is running, before its turn, is not called in that round.
- Calling `setValue` on any input cell from inside a callback throws an `Error`. The engine keeps working afterwards.
