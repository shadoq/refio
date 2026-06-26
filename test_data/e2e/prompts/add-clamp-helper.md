`normalize(score)` in `src/Main.kt` is meant to constrain a score to the inclusive range
`[0, 100]`, but it currently returns the raw value, so `normalize(150)` wrongly returns 150.

Do two things, in the same file:

1. Add a reusable helper `fun clamp(value: Int, min: Int, max: Int): Int` that returns `value`
   constrained to the inclusive `[min, max]` range.
2. Use that `clamp` helper inside `normalize` so a `score` is always clamped to `[0, 100]`.

Keep `main` exactly as it is. Implement `clamp` as a real min/max clamp — do not just inline a
one-off `if` in `normalize`.
