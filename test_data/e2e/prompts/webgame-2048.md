Create a complete single self-contained file `game_2048_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build the 2048 game.

Play on a 4x4 grid. Move tiles with the arrow keys and with touch or pointer swipes on the board, so it
works on mobile as well as desktop. A move slides every tile as far as it can go in that direction and
merges tiles of equal value: each tile may take part in at most one merge per move, merges happen
against the direction of travel in the correct order (the tile nearest the wall resolves first), and
two tiles never merge twice or merge with unequal values.

After every successful move spawn one new tile in a random empty cell, a 2 with 90 percent probability
and a 4 with 10 percent. Detect game over when no move can slide or merge anything, and detect the win
when a 2048 tile appears, showing a win screen that lets the player keep playing or start over.

Animate the board properly: tiles are positioned elements that transition smoothly when sliding, new
tiles pop in with a scale animation, merged tiles get a brief pulse, and colors follow the classic
value palette. Never flip the grid instantly in an un-animated redraw; the DOM must visibly show
slides and merges.

Track the score, adding the value of every merge, and persist the best score in localStorage across
sessions. Provide undo that restores the board and score to the state before the last move (at least
one level deep) and a new game button.
Every visible control must work; no fake simulation states. Deliver the one file `game_2048_model_01.html`.
