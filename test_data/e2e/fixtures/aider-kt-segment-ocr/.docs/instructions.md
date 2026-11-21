# Instructions

A meter reader prints numbers on a strip using a three-column, four-line character cell per digit. Turn the printout back into text.

## Cells

Each digit occupies a cell of 3 characters by 4 lines, drawn with spaces, `_` and `|`. The fourth line of a cell is always blank. The first three lines of each digit are exactly:

```text
0:  " _ " "| |" "|_|"      5:  " _ " "|_ " " _|"
1:  "   " "  |" "  |"      6:  " _ " "|_ " "|_|"
2:  " _ " " _|" "|_ "      7:  " _ " "  |" "  |"
3:  " _ " " _|" " _|"      8:  " _ " "|_|" "|_|"
4:  "   " "|_|" "  |"      9:  " _ " "|_|" " _|"
```

## API

```kotlin
fun readDigits(lines: List<String>): String
```

`lines` is a printout of one or more rows of numbers. Every row is 4 lines.

## Rules

- Rows are read in groups of 4 lines. The text of each row is joined with a comma: three rows give `"123,456,789"`.
- Editors and e-mail clients strip trailing spaces, so lines may be shorter than the grid. Before reading, right-pad every line with spaces to the length of the **longest line of the whole input**. Because of this, a shorter row ends with blank cells.
- A cell that is entirely blank (all 4 lines, 3 characters) reads as a single space `" "`.
- A cell that is not blank and does not match a digit in the table reads as `?`. This includes a cell that has anything but spaces in its fourth line. An unreadable cell never stops the other cells or rows from being read.
- No lines at all gives `""`.

## Errors

`IllegalArgumentException` when the number of lines is not a multiple of 4, or when the padded line length is not a multiple of 3.
