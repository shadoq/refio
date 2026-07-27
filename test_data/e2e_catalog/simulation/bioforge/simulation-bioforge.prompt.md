Create a complete single self-contained file `website_app_bioforge_{{MODEL_ID}}_01.html` (HTML + CSS + JavaScript inline; no
external libraries, CDNs, assets or backend). Simulate an evolving 2D ecosystem.

The world contains plants, herbivores, carnivores, food, water and obstacles. Each organism has a
genome: size, speed, vision range, metabolism, reproduction threshold, aggression, mutation rate,
lifespan. Organisms search for food, move, consume energy, avoid threats, reproduce, mutate, age
and die, and compete for limited resources. Use spatial partitioning so it stays fast.

Add environmental systems: a day/night cycle, temperature, rainfall, vegetation growth, and
occasional disease, with controls to influence them.

The interface must fill the viewport and provide: pause and speed, an organism inspector, a genome
viewer, population counts, average-trait charts over generations (drawn with canvas or SVG, no
chart libraries), an event log, a seed input, and spawn/disaster controls. Support save/load via
localStorage and JSON export/import.

The simulation should stay responsive with at least ~1000 organisms. Do not leave TODOs or empty
functions. Deliver the one file `website_app_bioforge_{{MODEL_ID}}_01.html`.
