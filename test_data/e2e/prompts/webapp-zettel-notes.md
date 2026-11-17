Create a complete single self-contained file `zettel_notes_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a Zettelkasten note-taking app.

Notes have a title, a markdown-ish body (headings, bold, italics, lists) and tags; the user can
create, edit and delete notes in an editor pane with a live preview of the rendered body. Parsing of
[[Wiki-Links]] must resolve to the note with exactly that title and render it as a clickable link
inside the preview.

Wiki-links automatically produce backlinks: the open note shows a backlinks panel listing every note
whose body links to it, recomputed live as bodies are edited, so links never go stale. Links to
titles that do not exist must still be visible but visibly marked as broken, and clicking a broken
link offers to create the missing note with that title. Deleting a note must clean up: it disappears
from search, tags and the graph, and links pointing to it become broken in their source notes.

Provide full-text search across titles, bodies and tags that filters as the user types, ranking title
matches above body matches and highlighting the hits in the result list. A tags sidebar lists every
tag with its note count and clicking a tag filters the note list; tags combine with the search query.

Render an interactive graph on a canvas: notes are nodes and wiki-links are edges, laid out by a real
force-directed simulation (node repulsion, edge springs, weak centering) that animates and settles,
re-runs when the graph changes, lets nodes be dragged with the mouse, highlights the currently open
note, and opens a note when its node is clicked.

Persist everything in localStorage and, on first run, seed a demo graph of at least eight
interconnected notes with tags so the graph and backlinks are populated immediately. Every visible
control must work; no fake simulation states. Deliver the one file `zettel_notes_model_01.html`.
