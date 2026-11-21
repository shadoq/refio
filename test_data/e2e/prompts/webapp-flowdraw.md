Create a complete single self-contained file `flowdraw_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a flowchart editor rendered on SVG.

Provide a palette with at least five node shapes: terminal, process, decision, input/output, database
and note. Nodes drag with grid snapping, edges connect an output port to an input port and are drawn
as bezier curves with arrowheads that stay attached while nodes move, and edges can be selected and
deleted. Nothing may render as disconnected invisible state: every stored node and edge must appear
and behave in the diagram.

Support marquee and multi-selection, delete, copy and paste, and undo and redo covering at least node
and edge add, move and delete operations. Pan by dragging the canvas, zoom with the mouse wheel, and
keep hit-testing and snapping accurate under any pan/zoom transform.

A validation report must flag dangling nodes and require every decision node to have exactly one
incoming and two outgoing edges, listing violations so they can be clicked and fixed. Persist
diagrams as JSON via localStorage with import and export, and export the whole diagram as a
standalone SVG file. Every visible control must work; no fake simulation states.
Deliver the one file `flowdraw_model_01.html`.
