# Instructions

Parse the text of an INI configuration file.

## API

`ini-parser.js` exports `parseIni(text)` (CommonJS, `module.exports = { parseIni }`). It returns a plain object:

```js
{ globals: { key: 'value', ... }, sections: { sectionName: { key: 'value', ... }, ... } }
```

`globals` holds the keys that appear before the first section header. All values are strings. Both `\n` and `\r\n` line endings are accepted. Lines are numbered from 1, and blank lines and comment lines count.

## Lines

- A **blank line** (only whitespace) is ignored.
- A **comment line** is a line whose first non-whitespace character is `;` or `#`. It is ignored.
- A **section header** is a line whose first non-whitespace character is `[`. It must look like `[name]`, optionally followed by whitespace and a comment (`; ...` or `# ...`). The name is trimmed and must not be empty. A section that appears again is merged into the earlier one.
- Any other line is `key = value`. Split at the **first** `=`: the key is the trimmed text before it and must not be empty; the value comes from the text after it. A later `=` belongs to the value (`a = b = c` gives `b = c`). Keys are case-sensitive and may contain spaces inside. A key that appears again in the same section replaces the earlier value (last one wins).

## Values

**Unquoted values** are trimmed. An inline comment starts at the first `;` or `#` that is preceded by whitespace and ends the line; it is not part of the value. A `;` or `#` that is not preceded by whitespace is part of the value, so `url = http://host/#top` keeps `#top`, and `color=#fff` is `#fff`. Single quotes have no special meaning.

**Quoted values** start with `"` (after the `=` and any whitespace). The value is the text up to the next unescaped `"`, with these escapes: `\"` is a double quote, `\\` a backslash, `\n` a newline and `\t` a tab. Whitespace, `;` and `#` inside the quotes are kept. After the closing quote only whitespace and an optional comment (`;` or `#` and the rest of the line) may follow.

## Continuation lines

A line that starts with a space or a tab and comes **immediately after** a `key = value` line or after another continuation line continues the value of that key: its trimmed text, with any inline comment removed as above, is appended to the value after a single `\n`. A blank line, a comment line or a section header between them ends the continuation, and an indented line after that is an ordinary line (so `  key = value` works as a key line). The value of a quoted `key = "..."` line cannot be continued.

## Errors

`parseIni` throws an `Error` whose `message` starts with `line N: ` (`N` is the line number of the offending line, followed by a short description) and which also has a numeric `line` property equal to `N`. The errors are:

- a line that is not blank, a comment or a section header and has no `=`;
- an empty key;
- a section header without the closing `]`, with an empty name or with other text after it;
- an unterminated quoted value;
- an unknown escape sequence in a quoted value;
- text other than whitespace or a comment after the closing quote;
- a continuation line after a quoted value.
