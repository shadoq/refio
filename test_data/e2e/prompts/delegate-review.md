There is a bug in `src/Validator.kt`.

First, use the `code-reviewer` subagent to review `src/Validator.kt` and identify the
problem. Then apply the fix yourself so the function behaves correctly.

The function `isAdult` should treat a valid adult age as 18 through 120 inclusive, but
it currently rejects someone who is exactly 18. Fix the boundary.
