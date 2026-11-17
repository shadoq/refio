Create a complete single self-contained file `image_editor_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a canvas image editor.

Load images through a file input and by drag-and-drop onto the window, drawing them to a canvas at
their real resolution with a fit-to-view zoom control. Keep the original image untouched so edits are
non-destructive and adjustable at any time.

Implement adjustment sliders for brightness, contrast, saturation and gamma that apply real per-pixel
ImageData math or equivalent CSS-filter math, with live numeric readouts. Add one-click filters —
grayscale, invert, sepia and blur — that compose correctly with the adjustment sliders instead of
overwriting them. Pixel operations must be genuine ImageData math; no faked previews.

Provide a crop tool with a draggable, resizable selection rectangle over the image that is applied for
real on confirm, plus rotate 90 degrees clockwise and counter-clockwise and horizontal/vertical flip;
geometric transforms must compose with the pixel pipeline. Draw a luminance or RGB histogram on a
separate canvas that updates after every change, with a before/after toggle (hold or click to compare
against the original).

Maintain an undo history of at least 10 operations (adjustment commits, filter applies, crop, rotate,
flip) with redo, and export the current image to PNG and JPEG (with a quality slider for JPEG) via a
download link built from toDataURL or a Blob. Show image dimensions, cursor position and the current
zoom level in a status bar, and warn before closing with unsaved changes. Every visible control must
work; no fake simulation states. Deliver the one file `image_editor_model_01.html`.
