Create a complete single self-contained file `tetris_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a full Tetris game.

Define the seven tetrominoes I, O, T, S, Z, J and L with their correct cell shapes and colors in a
piece table, spawning each piece at the top center of a 10 by 20 playfield drawn on a canvas. Draw new
pieces from a shuffled 7-bag randomizer so pieces stay reasonably distributed.

Implement rotation as super rotation system: rotate clockwise with left shift and counterclockwise with
right shift (or the same key pattern with Z and X), apply the standard JLSTZ and I wall kick tables so
pieces can rotate against walls and the stack, and let O rotate in place. Keep the kick tables in the
code as data, not approximations.

Provide a hold slot usable once per spawned piece, a next queue showing at least 5 upcoming pieces, and
a ghost piece showing the landing position. Support soft drop, hard drop with an instant lock, and a
lock delay of roughly half a second that resets on successful moves or rotations with a sensible cap.

Score line clears the standard way: 100, 300, 500 and 800 points times the level for single, double,
triple and tetris, with combo bonuses for consecutive clears and a back-to-back bonus for chained
tetris clears. Add small points for soft and hard drop distance. Every ten cleared lines the level
increases and gravity speeds up along a level curve. Run the game loop with requestAnimationFrame and
handle input with keydown so left, right, down, up-rotate, hold, pause and hard drop all respond.

Detect game over when a new piece cannot spawn, offer restart, pause and resume, and persist the best
score in localStorage, displaying it next to the current score and level.
Every visible control must work; no fake simulation states. Deliver the one file `tetris_model_01.html`.
