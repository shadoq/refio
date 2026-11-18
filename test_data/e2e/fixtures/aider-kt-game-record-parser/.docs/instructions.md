# Instructions

Parse a simplified game record into a tree of positions. A record stores the moves of a board game together with the alternative lines that were analysed.

## Format

```text
(;A[x]B[y](;C[z])(;D[w]))
```

- A record is one **tree**: `(`, one or more **nodes**, any number of nested trees, then `)`.
- A **node** starts with `;` and is followed by zero or more **properties**. `;` alone is a valid, empty node.
- A **property** is a key followed by one or more **values**. A key is one or more upper case ASCII letters (`A`, `AB`, `TR`). A value is text between `[` and `]`: `A[x]`, `A[x][y]` (two values), `A[]` (one empty value).
- Inside a value, a backslash escapes the next character, whatever it is: `\]` is a literal `]`, `\\` a literal backslash. Any other character, including `[`, `(`, `)`, `;`, spaces and line breaks, is kept as it is.
- Whitespace (space, tab, `\r`, `\n`) is allowed between tokens: around `(`, `)`, `;`, between a key and its first value, and between values. It is never allowed inside a key.
- Several nodes in a row, `;A[1];B[2];C[3]`, form a chain: each node is the only child of the one before it.
- Nested trees after the last node of a tree are **variations**: they become the children of that last node, in order.

## API

```kotlin
data class Node(
    val properties: Map<String, List<String>> = emptyMap(),
    val children: List<Node> = emptyList(),
)

fun parseRecord(text: String): Node
```

`parseRecord` returns the first node of the record. Example: `(;A[x]B[y](;C[z])(;D[w]))` gives a node with properties `A -> [x]`, `B -> [y]` and two children, one holding `C -> [z]` and one holding `D -> [w]`. `(;A[1];B[2](;C[3])(;D[4]))` gives `A` with one child `B`, which has the two children `C` and `D`.

## Errors

`parseRecord` throws `IllegalArgumentException` for anything that is not exactly one well formed record:

- empty or whitespace-only text, or text that does not start with `(`;
- a tree without a node, such as `()`, a missing `)`, or a node after a variation;
- a property with no value, a key that is not upper case letters, or the same key twice in one node;
- a value that is never closed;
- anything but whitespace after the closing `)` of the record.
