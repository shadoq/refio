Create a complete single self-contained file `minesweeper_model_01.html` (HTML + CSS + JavaScript
inline; no external libraries, CDNs, assets or backend). Build a Minesweeper game.

Offer three presets: beginner 9x9 with 10 mines, intermediate 16x16 with 40 mines and expert 30x16 with
99 mines, plus a custom dialog with width, height and mine inputs validated against sensible limits.
Place all mines only after the first click, excluding the clicked cell and its neighbors, so the first
reveal is always safe and typically opens an area.

Left click reveals a cell: number cells show the adjacent mine count in the classic colors, and empty
cells reveal recursively with a flood fill over their zero-neighbor region. Right click cycles each
cell through unmarked, flag and question mark, and flags can never be revealed by a normal click.
Support chording: middle click or pressing both buttons (or double click) on a revealed number whose
flag count matches opens all its unflagged neighbors at once.

Show a mine counter that counts down as flags are placed, and a timer that starts on the first click
and stops on win or loss, rendered with digit-style displays. Include the classic reset button that
changes face on loss and win. On loss reveal every mine, mark the mine that exploded, and highlight
wrongly placed flags; on win flag all remaining mines automatically.

Detect the win as every non-mine cell revealed, and persist the best time per difficulty and for
custom sizes in localStorage, showing the record after a win. Prevent the context menu from appearing
over the board.
Every visible control must work; no fake simulation states. Deliver the one file `minesweeper_model_01.html`.
