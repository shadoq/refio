Create a complete single self-contained file `spreadsheet_{{MODEL_ID}}_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a working spreadsheet with a real formula engine.

The grid has 26 columns (A to Z) and 100 rows, with a column and row header, a selected cell, a visible
formula bar and in-cell editing. Keyboard navigation must work: arrows, Tab, Enter, Escape to cancel an
edit, Delete to clear, and a range selection with Shift plus arrows.

A cell holds a literal (number or text) or a formula starting with `=`. The formula language supports
numbers, strings, the operators + - * / ^ and parentheses, comparisons, single cell references such as A1,
ranges such as B2:B10, and the functions SUM, AVG, MIN, MAX, COUNT and IF.

Build a dependency graph between cells: editing one cell recalculates only the cells that depend on it,
not the whole sheet. Detect circular references and show `#CYCLE` in every cell of the cycle instead of
hanging. A reference to an invalid cell shows `#REF`, a division by zero shows `#DIV/0`, and a formula that
cannot be parsed shows `#ERROR`, each with the underlying cause visible somewhere in the interface.

Add multi-level undo and redo for edits, clears and pastes, copy and paste of a range, import from pasted
CSV text and export of the sheet to CSV, and persistence of the whole sheet in localStorage so a reload
restores it. Every visible control must work and every displayed value must come from the engine, never
from a hardcoded table. Deliver the one file `spreadsheet_{{MODEL_ID}}_01.html`.
