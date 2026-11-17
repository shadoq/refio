Create a complete single self-contained file `markdown_studio_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a Markdown editor with live HTML preview.

Implement a from-scratch Markdown-to-HTML parser with no libraries: ATX headings, bold, italic,
strikethrough, inline code, fenced code blocks with a language label, ordered, unordered and nested
lists, blockquotes, pipe tables with column alignment, paragraphs, horizontal rules, links and
images. Escape all HTML in text nodes before applying inline formatting so raw markup in the source
cannot execute, and keep the parser tolerant of partial input while typing.

The interface is a two-pane layout with the editor on one side and the rendered preview on the other,
updated live as you type. A toolbar with bold, italic, heading, list, code and link buttons wraps or
prefixes the current selection, synchronized scrolling keeps both panes aligned, and a status bar
shows word, character and line counts for the document.

Persist the document to localStorage with debounced autosave plus an explicit manual save button,
restore the last document on load, and provide export to .md and to a standalone styled .html file
using Blob downloads. Every visible control must work; no fake simulation states.
Deliver the one file `markdown_studio_model_01.html`.
