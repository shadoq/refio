Create a complete single self-contained file `space_shooter_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a vertical space shooter.

The game loop runs on requestAnimationFrame with a scrolling multilayer starfield giving parallax depth.
The ship moves with arrow keys or WASD and fires with Space, holding to autofire. Define at least four
enemy types with genuinely different behavior, for example a drifter swaying in sine curves, a diver that
swoops toward the player, a gunship that stops and fires aimed shots, and a zigzag raider releasing short
bursts; each type has its own movement pattern, firing pattern, health and score value, and every bullet
is a real entity with hitbox collision.

Script the waves as data that escalates: enemy counts, the mix of types and spawn timing grow harder wave
by wave, with a wave banner and a short breather between waves. Every few waves spawn a boss with a health
bar at the top of the screen and two or three phases that change its movement and attack patterns as its
health drops, returning to the scripted waves once defeated.

Destroyed enemies drop at least three kinds of power-ups, such as a spread shot, a piercing laser and a
shield, that change the weapon or defense for a duration or until the ship is hit. The player has health
with a short invulnerability window and flicker after taking damage, particle explosions on every impact,
and screen shake scaled to the severity of the event. Track the score, persist the high score in
localStorage, pause and resume with a key, restart after death, and show a game-over screen with the final
score and the best score.

Every visible control must work; no fake simulation states. Deliver the one file `space_shooter_model_01.html`.
