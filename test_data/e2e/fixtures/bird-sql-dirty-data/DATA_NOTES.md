# Data quirks in the bike rental export

The three CSV files come from a legacy system and were loaded as they are. These quirks are known and every query has to cope with them.

## members

- `full_name` has leading and trailing spaces and inconsistent letter case (`"  ALVA BRANDT "`, `"boris keller  "`). The same person is always the same name after trimming and lowercasing.
- `joined` is a text date in one of two formats: `YYYY-MM-DD` or `DD/MM/YYYY` (day first). Both formats can appear in the same column.
- Some members appear twice: the duplicate rows are identical to each other. `id` identifies a member.

## bikes

- `station` has trailing spaces in some rows (`"Central "` and `"Central"` are the same station).

## rentals

- `status` is one of `completed`, `cancelled` or `active`, but with inconsistent case and stray spaces (`"COMPLETED "`, `" completed"`, `"Cancelled"`).
- `start_date` uses the same two formats as `members.joined`.
- `minutes` is empty (loaded as NULL) when the duration is unknown. Unknown durations are ignored in averages.
- `fee_cents` is empty (loaded as NULL) when the fee was waived. A waived fee counts as 0 cents in totals, and the rental itself still counts.
- Some rentals appear more than once: duplicate rows are identical to each other. `id` identifies a rental, and a duplicated rental must be counted once.
