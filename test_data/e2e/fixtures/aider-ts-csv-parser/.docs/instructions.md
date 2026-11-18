# Instructions

Implement `parseCsv(text, options)` in `src/csv.ts`. It turns CSV text into an array of records, each record an array of field strings.

`CsvError` is already declared in the stub; keep it as it is.

## Records and fields

- Records are separated by `\n` or `\r\n`. A lone `\r` that is not followed by `\n` is ordinary data.
- A final line break at the very end of the text does not start another record. Empty text gives `[]`.
- A blank line in the middle of the text is a record with a single empty field: `[""]`.
- Fields are separated by the delimiter, `,` by default. `options.delimiter` may set another single character. It must be exactly one character and not `"`, `\n` or `\r`, otherwise `parseCsv` throws `RangeError`.
- Fields are not trimmed. A delimiter right before a line break or the end of the text ends the record with an empty last field: `"a,"` gives `[["a", ""]]`.

## Quoted fields

- A field is quoted only when its very first character is `"`. Inside it the delimiter and line breaks are ordinary data, kept exactly as they are (a `\r\n` inside quotes stays `\r\n`).
- Two quotes in a row inside a quoted field stand for one quote character. `""` on its own is an empty field.
- The closing quote must be followed by the delimiter, a line break or the end of the text. Anything else throws a `CsvError`.
- A `"` that is not the first character of a field (including after leading spaces) is plain data and needs no escaping.

## Errors

`CsvError` carries the 1-based `line` and `column` where the problem was found. Lines are counted by `\n`, including the ones inside quoted fields. The column counts characters from the start of the line.

- A quoted field that is never closed: message `Unterminated quote at line L, column C`, positioned at the opening quote.
- A character after a closing quote: message `Unexpected character after closing quote at line L, column C`, positioned at that character.
