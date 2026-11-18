# Instructions

Tally the results of a small football league and return the standings as text.

## Input

`tally(rows)` receives a list of strings, one match per line:

```text
Home Team;Away Team;3-1
```

Leading and trailing whitespace around a line and around each field is ignored, and blank lines are skipped.

A line is invalid when it does not have exactly three `;`-separated fields, when a team name is empty, when a team plays itself, or when the score is not two non-negative integers separated by `-`. An invalid line raises `ValueError` whose message starts with `line N:` where `N` is the 1-based position of the line in `rows` (blank lines count).

## Points

A win is worth 3 points, a draw 1, a loss 0.

## Order

Teams are ordered by, in this order:

1. points, highest first;
2. head-to-head points: among the teams that are level on points, the points each of them earned in the matches played *between those teams only*, highest first;
3. overall goal difference, highest first;
4. overall goals scored, highest first;
5. team name, alphabetical.

Head-to-head is computed once for each group of teams level on points; it is not re-applied to smaller groups that remain tied.

## Output

The first line is the header, then one line per team. Columns are separated by ` | `:

```text
Team                           | MP |  W |  D |  L |  GD |  P
Blue Bears                     |  2 |  1 |  1 |  0 |  +2 |  4
```

- `Team`: the name, left-aligned and padded to 30 characters.
- `MP`, `W`, `D`, `L`, `P`: right-aligned in 2 characters.
- `GD`: goal difference, right-aligned in 3 characters, always with a sign (`+0` for zero).

Lines are joined with `\n`, without a trailing newline. With no matches the result is the header alone.
