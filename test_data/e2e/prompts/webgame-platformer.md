Create a complete single self-contained file `platformer_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a tile-map platformer.

Define at least three hand-designed levels as arrays of strings, each character mapping to a tile, with
solid ground and platforms, gaps, higher ledges, coins, checkpoint flags, patrolling enemy spawn points
and an exit door; the levels must be wider than the viewport so scrolling matters and should grow in
difficulty. The player runs with the arrow keys, accelerating with a top speed and slowing through
friction, and jumps with variable height: holding jump sustains the rise while releasing early cuts it
short. Add coyote time so a jump still registers briefly after walking off a ledge, and jump buffering so
a press shortly before landing fires the jump on touchdown.

Resolve collisions against solid tiles from all four sides every frame, so the player never sinks into
floors, clips through walls or teleports through corners at speed. Include at least one enemy type that
patrols a platform, turning at walls and edges, that kills the player on side contact but is destroyed by
a stomp from above with a small bounce. Losing a life respawns the player at the last touched checkpoint,
or at the level start if none was reached, keeping the coins already collected.

A camera follows the player smoothly and clamps to the level bounds so no empty space is shown. The HUD
shows lives, coins and the current level number, coins increment a counter with a pickup effect, and
reaching the exit door transitions to the next level through a short overlay. Clearing the final level
shows a win screen with the coin total and elapsed time plus a restart option.

Every visible control must work; no fake simulation states. Deliver the one file `platformer_model_01.html`.
