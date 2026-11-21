# Star Dodger

`game.js` is the mini canvas game on the arcade page: one `StarDodger`
instance is created per page and reused - `start()` runs a round, `stop()`
returns to the menu, `start()` begins the next round. Input arrives as
document-level keydown events; the loop runs on requestAnimationFrame.

The acceptance gate drives the lifecycle against stubbed browser APIs:

    node checks.js
