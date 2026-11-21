## Star Dodger slows down and keeps steering after returning to the menu

### Symptom

Every round of Star Dodger on the arcade page makes the fan spin up. Players
who restart a few rounds report the game gets choppy, and the ship keeps
steering with the arrow keys while the menu is open - so the menu's own key
navigation fights the game. The frame budget graph shows the requestAnimationFrame
loop never stops after `stop()`.

### How to reproduce

1. Run the gate on the current code: `node checks.js` - the lifecycle checks
   fail.
2. In a browser: play a round, return to the menu, press ArrowLeft - the ship
   moves although no round is running. Repeat three rounds and the keydown
   handlers stack up, one more per round.

### Expected behaviour

`stop()` must fully tear the round down: the keydown handler is removed again,
the animation loop is cancelled, and no frames are scheduled or handled once
the game is stopped. Starting the next round must register exactly one keydown
handler, no matter how many rounds were played before, and a running round
must animate, steer and score exactly as it does today.

### Your task

1. Reproduce both leaks in `game.js`: the listener registered inside `start()`
   on every restart (with a fresh closure that could never be removed) and the
   missing teardown in `stop()`.
2. Fix `game.js` in place: keep a bound handler so the same function reference
   goes to `addEventListener` and `removeEventListener`, and cancel the
   animation frame in `stop()`; keep the gameplay (update/draw/onKey) and the
   export unchanged.
3. Run the gate: `node checks.js` - it drives start/stop cycles against a
   stubbed document and requestAnimationFrame, counts listeners and frames, and
   must pass.

Do not modify `checks.js` or the README: the fix belongs in `game.js`.
