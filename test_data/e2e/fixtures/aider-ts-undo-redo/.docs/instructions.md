# Instructions

Implement `TextBuffer`, a small text buffer with undo and redo, in `src/text-buffer.ts`.

## State

- `new TextBuffer(initial = "")` starts with `initial` as its text and the cursor at position 0.
- `text` (getter) is the current text, `cursor` (getter) is the current cursor position.
- `setCursor(pos)` moves the cursor. It throws `RangeError` when `pos` is not an integer between 0 and the text length. Moving the cursor is not an edit and is never undone.

## Edits

- `insert(pos, str)` inserts `str` at `pos` and leaves the cursor right after the inserted text.
- `delete(pos, length)` removes `length` characters starting at `pos` and leaves the cursor at `pos`.
- Both throw `RangeError` when the range does not lie inside the text (`pos` below 0, above the length, or `pos + length` above the length, or `length` negative). A rejected call changes nothing.
- An insert of an empty string and a delete of length 0 do nothing and are not recorded as history.

## History

- Every edit is one undo step, except edits made inside a group (see below).
- `undo()` reverts the most recent step and returns `true`; with nothing to undo it returns `false`. After an undo the cursor is where it was just before that step began.
- `redo()` re-applies the most recently undone step and returns `true`; with nothing to redo it returns `false`. After a redo the cursor is where it was just after that step ended.
- Making a new (recorded) edit discards everything that could be redone.

## Groups

- `beginGroup()` and `endGroup()` make all edits between them one single undo step. Groups can be nested: only the outermost pair counts.
- An empty group, or one holding only no-op edits, leaves no history.
- `endGroup()` without a matching `beginGroup()` throws `Error`.
- `undo()` and `redo()` throw `Error` while a group is open.
- The first edit inside a group discards the redo history, as any edit does.
