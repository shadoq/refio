Create a complete single self-contained file `whiteboard_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a collaborative-style drawing whiteboard.

Drawing tools: a freehand pen and a marker with pressure-like width variation along the stroke, both
smoothed with quadratic curves between sampled points so strokes look smooth rather than raw 1px
polylines; an eraser; shape tools for straight line, rectangle, ellipse and arrow that show a live
preview while dragging and commit the shape on release; a text tool that places an editable text
block where the user clicks. Provide a color picker with a preset palette plus a recent-colors
strip, and an adjustable brush width slider that affects pen, marker and shapes.

Strokes and shapes must be stored as vector-like objects (tool, points or geometry, color, width,
layer id) and re-rendered every frame, never flattened into a bitmap you cannot undo. Implement at
least 3 layers, each holding its own objects, with a layer panel supporting reorder, visibility
toggle, per-layer opacity, and clear layer.

History: an undo/redo stack that correctly survives at least 50 operations across tools and layers,
including stroke, shape, text and layer-clear actions. Navigation: pan the board by dragging with
space or the middle mouse button, zoom with the mouse wheel or buttons around the cursor position,
and a reset-view control; the board canvas must be larger than the viewport and resize with the
window.

Add PNG export of the fully composed board (all visible layers, honoring opacity) via canvas
toDataURL or a Blob download, keyboard shortcuts for tools and undo/redo, pointer-event support for
mouse and touch, and a visible status bar with tool, layer, zoom level and object count. Every
visible control must work; no fake simulation states. Deliver the one file `whiteboard_model_01.html`.
