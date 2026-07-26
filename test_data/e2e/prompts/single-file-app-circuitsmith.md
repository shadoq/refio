Create a complete single self-contained file `circuitsmith_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a digital logic circuit simulator.

Provide a visual editor with draggable components, input/output pins, interactive wires, wire
routing, pan/zoom, snap-to-grid, multi-selection, copy/paste, undo/redo, rotation and deletion.

Components: input switch, push button, clock, constants, NOT, AND, NAND, OR, NOR, XOR, XNOR,
tri-state buffer, LED, seven-segment display, multiplexer/demultiplexer, half and full adders,
D and JK flip-flops, register and counter.

The engine must propagate digital states, support 0/1/undefined and high-impedance, detect unstable
feedback with an iteration limit, simulate clocks, visualize wire states, and support single-cycle
stepping.

Add truth-table generation, timing diagrams, a configurable clock, probes, a component inspector
and circuit validation. Provide example circuits (half adder, 4-bit adder, binary counter). Support
save/load via localStorage and JSON import/export. Every visible control must work; no fake
simulation states. Deliver the one file `circuitsmith_model_01.html`.
