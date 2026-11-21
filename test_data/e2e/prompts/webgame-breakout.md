Create a complete single self-contained file `breakout_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a Breakout/Arkanoid game.

Render the playfield on a canvas with a paddle at the bottom and a ball. Move the paddle with both the
mouse and the arrow keys. Bounce the ball correctly off walls, paddle and bricks: the reflection off
the paddle depends on where the ball hits it, so hitting the edges sends the ball at a sharper angle.
Losing the ball below the paddle costs a life; the player starts with three lives.

Define bricks in data with types: one-hit bricks in several color tiers, two- and three-hit bricks that
change color as they crack, and unbreakable steel bricks that bounce but never break. Build at least
four levels as explicit brick layouts with different shapes and mixes of brick types, and advance to
the next level when all breakable bricks are gone, with a short transition message between levels.

Drop falling power-up capsules from some destroyed bricks and make them actually work when caught: a
multi-ball that splits the ball into several, a wider paddle for a limited time, laser shots fired with
a key while active, a slow ball, and an extra life. Time-limited effects must expire and restore the
normal paddle or speed.

Give each brick type its own score value, show score and lives on screen, spawn small particles when
bricks crack or break, and pause the game on a key with a visible overlay. Add a start screen, a win
screen after the last level and a game-over screen with restart.
Every visible control must work; no fake simulation states. Deliver the one file `breakout_model_01.html`.
