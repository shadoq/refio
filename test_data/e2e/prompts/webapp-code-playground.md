Create a complete single self-contained file `code_playground_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build an HTML/CSS/JavaScript coding playground.

Provide three editor panes for HTML, CSS and JavaScript, each with a line-number gutter, Tab key
insertion of spaces, and editing that cooperates with the browser undo stack. The preview renders
in a sandboxed iframe assembled from the three panes via srcdoc.

Capture console output from the iframe for real: a small bridge script inside the srcdoc document
overrides console.log, console.info, console.warn and console.error and forwards each call with its
arguments to the parent window via postMessage, and the parent renders a console panel with level
badges, timestamps and a clear button. Uncaught runtime errors in the iframe surface with line hints
as an overlay or a console entry; the capture must be real, not decorative.

Add a Run button, debounced auto-run while typing, a Reset button, and at least three working example
templates (for example a DOM interaction demo, a canvas animation and an async timer demo) that load
complete content into all three panes. Persist all three panes to localStorage and restore them on
load. Every visible control must work; no fake simulation states.
Deliver the one file `code_playground_model_01.html`.
