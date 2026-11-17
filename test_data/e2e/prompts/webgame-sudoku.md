Create a complete single self-contained file `sudoku_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a Sudoku game with a real generator and solver.

Generate puzzles for real: fill an empty 9x9 board into a full valid solution using randomized
backtracking, then dig cells out one by one in random order, and after each removal run a solution
counting backtracking solver; keep the removal only if the puzzle still has exactly one solution, and
stop at the target clue count for the chosen difficulty. Offer four difficulties with distinct clue
counts, for example around 40, 34, 28 and 24 clues, and never fake uniqueness or ship a preset board.

Input works with mouse and keyboard: click a cell to select it, move the selection with the arrow keys,
and type 1 to 9 to place a digit or 0, backspace or delete to clear. Highlight the selected cell, its
row, column and box peers, and mark conflicting duplicates and any digit that contradicts the solution
or the current grid as a mistake with a visible style. Pencil-mark mode toggles small candidate notes
inside a cell instead of placing a digit, and notes can be edited and are cleared from peers when a
real digit is placed there.

Provide a hint button that uses the solver to fill one correct digit into the selected or a random
empty cell, a check button that flags all currently wrong entries, and a new game control with the
difficulty choice. Run a timer that starts with the first move, pauses with the game, and stops on
completion; detect the completed grid and show a win overlay. Persist the best time per difficulty in
localStorage and display the records.
Every visible control must work; no fake simulation states. Deliver the one file `sudoku_model_01.html`.
