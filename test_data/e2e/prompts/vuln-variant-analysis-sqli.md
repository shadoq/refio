You are doing the variant analysis for SEC-ADV-2026-031 (see `SECURITY.md` and
`CHANGELOG.md`). The advisory fixed one function, `find_offer` in `shop/offers.py`, that
spliced a caller-supplied value into its SQL text. The same pattern was written into the
package more than once, and the advisory author only looked at the function that was
reported. Find every sibling occurrence that is still unfixed.

1. Read the query modules under `shop/` and decide, function by function, whether a
   caller-supplied value reaches the SQL text through formatting (`f"..."`, `%`, `.format()`)
   or concatenation. The command-line front end `cli.py` shows where the input comes from.
   Functions that pass the value as a bound parameter are not siblings, even when they build
   the SQL text with an f-string (to place `?` placeholders) or concatenate the value into a
   bound parameter; neither are functions that interpolate only constants or allow-listed
   fragments, or that take no caller input.

2. Write `report/variants.md`. Each sibling gets its own line in exactly this form, with the
   path relative to the project root and the function name alone:

   ```
   variant = shop/<module>.py | <function>
   ```

   List every sibling and nothing else in this form: do not list the already fixed
   `find_offer`, and do not list functions that are safe. Around the lines, say briefly how
   each sibling is reached and which functions you checked and ruled out.

3. Fix every sibling with bound parameters (`?` placeholders passed to `execute`). The
   queries must return the same rows for legitimate input as before: names and titles with
   apostrophes such as `O'Brien` or `Artist's Easel` must keep matching. An injection payload
   must come back as the ordinary string it is - no rows, no error, no change to the
   database.

`check.py` is the acceptance gate. It calls every query function of the package against the
seeded database with honest input and with injection payloads, and it compares your list of
siblings with the reviewed list (stored as digests; it does not say which line is wrong). Run
it with `python3 check.py` and iterate until it passes. `python3 -m unittest` should stay
green.

Do not modify `check.py`, the seed data (`shop/seed.py`, `seed.py`, `shop/db.py`),
`shop/offers.py`, `cli.py`, `SECURITY.md`, `CHANGELOG.md` or the README.
