# Instructions

Convert a small subset of Markdown to HTML. Implement `Markdown.toHtml(String)` in `src/Markdown.java`.

## Blocks

The input is split into lines at `\n` or `\r\n`. Every line belongs to exactly one block. The output is the HTML of the blocks, in order, joined with nothing in between (no newlines, no indentation).

- **Header.** A line that starts, in column 0, with 1 to 6 `#`, then at least one space, then text. It becomes `<h1>` to `<h6>` by the number of `#`, with the text trimmed. `#######` (7 or more) is not a header, and neither is `#` directly followed by text, a lone `#`, or a line that has leading spaces: all of those are ordinary text.
- **List item.** A line that starts, in column 0, with `* ` followed by some text. Consecutive list items form one `<ul>` with one `<li>` per item (text trimmed). A blank line, a header or a paragraph line ends the list; a later item starts a new `<ul>`. A line such as `*bold` or a lone `*` is ordinary text.
- **Blank line.** A line that is empty or only whitespace. It produces no output, but ends the current paragraph and the current list.
- **Paragraph.** Consecutive ordinary text lines (a header, a list item or a blank line ends the run). The lines are trimmed and joined with a single space into one `<p>`.

An empty input, or one made only of blank lines, gives an empty string.

## Inline text

Headers, list items and paragraphs support emphasis:

- `__text__` is `<strong>text</strong>` and `_text_` is `<em>text</em>`. They can be nested in each other: `__bold _both_ bold__` and `_it __both__ it_`.
- A run of underscores is a marker only when it is exactly one or two underscores long. Three or more in a row are plain text.
- A marker can open emphasis only when the character before it is not a letter or digit (or it is at the start) and the character after it exists and is not whitespace. It can close emphasis only when the character before it is not whitespace and the character after it is not a letter or digit (or it is at the end). So underscores inside words (`snake_case_name`) are plain text, and so is `__ spaced__`.
- A closing marker closes the nearest still open marker of the same length; any open marker of another length in between stays plain text. Markers that never find a partner stay plain text.
- Emphasis never crosses a block boundary, because it is applied to each block's own text.

Everything else is plain text, in which `&`, `<` and `>` must be written as `&amp;`, `&lt;` and `&gt;`.

## Examples

```text
"# Title"              -> "<h1>Title</h1>"
"a\nb"                 -> "<p>a b</p>"
"* x\n* _y_"           -> "<ul><li>x</li><li><em>y</em></li></ul>"
"__a _b_ c__ and d_e"  -> "<p><strong>a <em>b</em> c</strong> and d_e</p>"
```
