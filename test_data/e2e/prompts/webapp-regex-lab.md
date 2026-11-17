Create a complete single self-contained file `regex_lab_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a regular-expression laboratory.

Provide a pattern input with toggle buttons for the flags g, i, m, s and u, and live validation: when
the pattern fails to compile, report the error message and character position inline instead of
crashing or throwing into the console. As the user types, re-run matching against an editable test
text and render match highlighting over the text itself, handling adjacent and zero-length matches
correctly (with the g flag, advance lastIndex past empty matches) and showing a match count.

Show a results table listing each match's value, index and length plus all capture groups, supporting
named groups like (?<name>...) and labeling unnamed groups by number. Add a replace preview: an input
for a replacement string where $1, $<name>, $& and other substitution references are applied and the
resulting output string is shown live next to the original.

Include a cheatsheet library of at least 8 ready-made patterns — email, URL, date, IPv4 address, hex
color, phone number, duplicate words, HTML tag — each loading its pattern, flags and a sample text
into the editor with one click. Add a plain-English explanation panel that breaks the current pattern
into tokens (literals, character classes, quantifiers, groups, anchors, alternation) and describes
each in one line, like a step-by-step debugger readout.

Persist the last pattern, flags and test text in localStorage and restore them on load. Evaluation
must be safe and bounded: run matching in chunks across animation frames or cap total work and time
so long or catastrophic patterns can never freeze the UI, surfacing a timeout notice instead. Every
visible control must work; no fake simulation states. Deliver the one file `regex_lab_model_01.html`.
