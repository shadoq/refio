## Billing export drops the last column whenever it is empty

### Symptom

Since Tuesday's deploy the nightly billing export is misaligned: every row whose
final column is empty (`sku-1,2,`) comes back one field short, so `pad_row`
shifts the following columns left and the invoice batch rejects the file. Rows
ending in a non-empty value are fine, which is why the visible unit suite never
noticed - it never covers an empty trailing field.

### How to reproduce

    python3 - <<'PY'
    from csvlite import split_row

    print(split_row("a,b,"))   # ['a', 'b']      (expected ['a', 'b', ''])
    print(split_row(",,"))     # []              (expected ['', '', ''])
    print(split_row(""))       # []              (expected [''])
    PY

`python3 -m unittest discover -s test` stays green on the current code.

### Expected behaviour

`split_row` must return every field of the row, including trailing empty ones:
a row that ends on a separator has an empty final field, a row of only
separators is all empty fields, an empty row is one empty field, quoting and
`""` escapes keep working, and `parse` / `pad_row` line up with the header
again.

### Your task

1. Reproduce the misalignment in `csvlite.py` and find the branch at the end of
   `split_row` that decides whether the leftover field buffer is returned.
2. Fix `csvlite.py` in place so the final field is always part of the result,
   keeping the scanner, the quoting rules and the public functions
   (`split_row`, `parse`, `pad_row`) unchanged.
3. Document the corrected trailing-field behaviour in the module docstring:
   add the `split_row('a,b,')`, `split_row(',,')` and `split_row('')`
   examples so the next reader does not reintroduce the guard.
4. Run the gate: `python3 checks.py` - it replays the corrupted export, the
   quoting regressions and the visible suite, and must pass.

Do not modify `checks.py`, the tests in `test/`, or the README: the fix belongs
in `csvlite.py`.
