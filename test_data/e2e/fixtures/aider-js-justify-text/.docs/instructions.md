# Instructions

Format a paragraph of text into lines of a fixed width, with full justification.

## API

`justify.js` exports `justify(text, width)` (CommonJS, `module.exports = { justify }`). It returns an array of strings, one per output line.

`width` must be an integer of at least 2, otherwise a `RangeError` is thrown.

## Words

The text is split into words on any run of whitespace (spaces, tabs, newlines); leading and trailing whitespace is ignored. Text without words gives `[]`. Lengths are JavaScript string lengths.

## Packing

Words are placed greedily in order: a word goes on the current line when the words already there, separated by single spaces, plus one space plus the word still fit in `width`; otherwise it starts a new line. A word of exactly `width` characters fits on a line of its own.

## Justification

- A line that is **not the last** and has **two or more words** is padded to exactly `width` characters by widening the gaps between its words. Every gap gets at least one space; the spaces that are left over are given out one per gap from the **left**, so the leftmost gaps are the widest (`a  b  c d` for width 9).
- The **last line** is left-aligned: its words are separated by single spaces and it is not padded.
- A **line with a single word** is left-aligned too and is not padded, wherever it is.

No line ever has trailing spaces.

## Long words

A word longer than `width` cannot be placed. When one is met, the line being built is closed first (it is justified like any non-last line, or it does not exist when the previous word ended a line). Then, while the rest of the word is longer than `width`, one line is emitted with its first `width - 1` characters followed by `-`, which is exactly `width` characters, and those `width - 1` characters are removed from the rest of the word. The remainder, which is at most `width` characters, is then placed like an ordinary word, so the words that follow can share its line.

## Example

```js
justify('a b c d eeeeeeeee', 9); // ['a  b  c d', 'eeeeeeeee']
justify('abcdefghijk', 10);      // ['abcdefghi-', 'jk']
```
