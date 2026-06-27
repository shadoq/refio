There is a null-safety bug in this project. `describe(x: String?)` in `src/Main.kt`
dereferences `x` without checking for null, so `describe(null)` crashes at runtime.

Find the bug and fix it with a minimal null check so the function handles a null
argument gracefully (return something sensible like `"length=0"` or `"null"` for the
null case). Do not change unrelated code. Make sure the file still compiles.
