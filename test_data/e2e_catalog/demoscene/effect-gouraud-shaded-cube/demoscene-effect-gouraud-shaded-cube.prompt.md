Build a realtime Gouraud-shaded rotating cube in a single self-contained file
`effect_gouraud_shaded_cube_{{MODEL_ID}}_01.html` using Canvas 2D only (no WebGL, no external libraries).

Implement a minimal 3D pipeline: vertices, rotation matrices, perspective projection, and backface
culling. Split faces into triangles and do per-vertex lighting (Lambert), then rasterize the
triangles with color interpolation across each face (Gouraud shading). Add a moving light source,
a wireframe toggle (W), and pause (P). Display the current FPS.

Deliver the one file `effect_gouraud_shaded_cube_{{MODEL_ID}}_01.html`.
