# Instructions

Implement a tiny stack language with user-defined words.

## The machine

`StackMachine` keeps a stack of integers and a table of words. Both survive between calls to `run`. `run(source)` executes `source` and returns a copy of the stack as a list, bottom first. The `stack` property returns the same copy without running anything.

## Tokens

`source` is split on whitespace. Word names are case-insensitive: they are lower-cased before use. A token made of an optional `-` followed by one or more digits is a number and pushes that integer. Every other token is a word.

## Built-in words

| word   | effect |
|--------|--------|
| `+` `-` `*` | pop `b`, pop `a`, push `a op b` |
| `/`    | pop `b`, pop `a`, push `a / b` as an integer division that truncates toward zero (`-7 2 /` is `-3`) |
| `dup`  | duplicate the top value |
| `drop` | remove the top value |
| `swap` | swap the two top values |
| `over` | push a copy of the second value from the top |

## Definitions

`: name body... ;` defines a word. The body is a sequence of numbers and words.

- A definition captures the meaning of every word in its body at the moment it is defined. Redefining a word later does not change the words that were defined earlier using it. A body may use the word being defined: it then refers to the previous meaning (`: a 1 ; : a a a + ;` makes `a` push `2`).
- Built-in words can be redefined like any other word, and the definitions made before the redefinition keep the built-in behaviour.
- A word used in a body must be known at the time of the definition; otherwise the definition fails with `unknown word: <name>`.
- A definition does nothing when it is made. It only changes what the name means from then on.

## Errors

Every error is a `StackLangError` with exactly this message:

| situation | message |
|-----------|---------|
| an operation needs more values than the stack has | `stack underflow` |
| division by zero | `divide by zero` |
| a word that is neither built-in nor defined (outside or inside a definition) | `unknown word: <name>` (lower-case) |
| the name in a definition is a number, such as `: 5 1 ;` or `: -3 1 ;` | `cannot define number: <token>` |
| `:` with no name, which is the end of the source or a `;` | `missing word name` |
| `:` inside a definition | `nested definition` |
| source ends inside a definition | `unterminated definition` |
| `;` outside a definition | `unexpected ;` |

When `run` raises, the stack and the word table are exactly as they were before that call to `run`, even if the failing source had already executed some words or made some definitions.
