# Instructions

Compare two lists of lines and report the differences in a unified-diff-like format.

## Input and output

`diff(old, new, context=1)` takes two lists of strings (lines without line terminators) and returns a list of strings. Lines are compared exactly, including leading and trailing spaces. When there is no difference the result is `[]`. A negative `context` raises `ValueError`. The inputs are not modified.

## Edit script

The edit script is derived from a longest common subsequence (LCS) of the two lists, and it is deterministic. Walk both lists from the start, at positions `i` in `old` and `j` in `new`:

1. if `old[i] == new[j]`, the line is unchanged. Equal lines are always matched at once, which means that among equally good alignments the leftmost matches win;
2. otherwise, if `old` still has lines and either `new` is used up or removing `old[i]` keeps the common subsequence of the remainders just as long as inserting `new[j]` would, the line `old[i]` is *deleted*. A deletion is preferred over an insertion whenever both are possible;
3. otherwise `new[j]` is *inserted*.

A consequence is that inside a block of changes, all deletions come before all insertions.

## Hunks

The changes are shown in hunks with `context` unchanged lines before and after each block of changes (default 1). Two blocks of changes belong to the same hunk when at most `2 * context` unchanged lines separate them; otherwise they are separate hunks. A hunk that would start or end beyond the first or last line is simply cut there.

Every hunk starts with the line

```text
@@ -<old_start>,<old_count> +<new_start>,<new_count> @@
```

followed by its lines, each prefixed with a space (unchanged), `-` (deleted) or `+` (inserted).

- `old_count` is the number of unchanged plus deleted lines of the hunk and `new_count` the number of unchanged plus inserted lines. Both counts are always written, also when they are `1` or `0`.
- `old_start` is the 1-based line number in `old` of the first unchanged or deleted line of the hunk. When `old_count` is `0` it is instead the number of the `old` line just before the hunk (`0` at the top of the file). `new_start` follows the same rules for `new`.

## Example

```python
diff(["a", "b", "c", "d", "e"], ["a", "b", "X", "d", "e"])
# ["@@ -2,3 +2,3 @@", " b", "-c", "+X", " d"]
```
