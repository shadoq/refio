The file `plumber.html` is a small single-file canvas game ("Pixel Plumber C64"). It has no pause
feature yet.

Add a pause feature to the existing game, editing `plumber.html` in place:

- Pressing `Escape` while playing pauses the game (freeze updates) and shows a "PAUSED" overlay on the
  canvas; pressing `Escape` again resumes.
- While paused, the game loop must keep drawing the overlay but must NOT advance the player or score.

Make the smallest change that adds this cleanly. Do not rewrite unrelated parts, do not remove the
existing movement, jump, coin, score, or game-over behavior, and keep it a single self-contained file.
