Write a single self-contained file `levels.html` (HTML + CSS + JavaScript inline; no external
libraries or assets) that renders tile-based level maps on a `<canvas>` in a retro C64 pixel style.

Define exactly four levels in a JavaScript array. Each level has a `name` and a `tiles` field that is
an array of equal-length strings, one string per row, drawn top-to-bottom. Use `'.'` for an open/empty
cell and `'#'` for a solid block. Most rows near the top of a level are wide-open sky (long runs of
`'.'`), with solid ground and platforms lower down — so the data is intentionally repetitive.

The four levels, in order, must be named exactly: `Boot Valley`, `Crystal Cache`, `Frozen Stack`,
`Lava Kernel`. Each level should be at least 40 columns wide and 12 rows tall.

On load, draw the first level's tiles to the canvas as colored pixel blocks (one color for `'#'`, the
sky color for `'.'`). Left/Right arrow keys switch to the previous/next level and redraw; show the
current level's `name` as on-canvas text. Keep it simple and complete — no empty functions, no TODOs.
Put everything in the one file `levels.html`.
