You are a senior HTML5/JavaScript game developer. Build a complete, playable retro platformer
`Pixel Plumber C64` as a single self-contained file `plumber.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets, or backend). It must run by simply opening the file in a browser.

Style: 8-bit Commodore-64 look, pixel-art drawn in code on a `<canvas>`, low logical resolution,
limited palette, tile-based levels, simple animations, a retro HUD, and a menu screen. The hero is a
plumber-like adventurer named `Pip`; the second racer / CPU is `Zip`. Enemies are your own characters
(e.g. `Crabs`, `Bit Beetles`, `Spark Slimes`). Collectibles are `gold bolts`. The level exit is a
glowing service gate. Do not copy any protected names, art, or levels.

Implement a side-scrolling 2D platformer with gravity, jumping, tile collision, enemies, score, lives,
a countdown timer, level progression, and a game-over screen.

Game modes: `1 Player` (you control Pip), `1 Player + CPU` (Pip is you, Zip is AI racing to the gate),
`CPU + CPU` (both AI, demo). A menu lets you choose mode, difficulty (`Easy`/`Normal`/`Hard`), theme
(`C64 Classic`, `Night Circuit`, `Jungle Chips`, `Ice Memory`, `Lava Core`), and a starting level.

Add at least 4 levels: `Boot Valley` (gentle start), `Crystal Cache` (caves, bigger gaps),
`Frozen Stack` (slippery platforms), `Lava Kernel` (lava + faster enemies). Difficulty affects lives,
time, enemy speed, hazard count, and the CPU's mistake rate. The CPU must visibly work: it moves toward
the gate, jumps before pits/obstacles, tries to avoid enemies, and makes mistakes by difficulty.

Controls: A / Left = move left, D / Right = move right, W / Space / Up = jump, Escape = pause.

Prioritize a complete, stable, playable MVP over perfect physics. Do not leave empty functions, TODOs,
or descriptions instead of implementation. Put everything in the one file `plumber.html`.
