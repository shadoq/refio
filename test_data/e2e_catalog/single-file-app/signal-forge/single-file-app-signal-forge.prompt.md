Create a complete single self-contained file `website_app_signal_forge_{{MODEL_ID}}_01.html` (HTML + CSS + JavaScript inline;
no external libraries, samples or assets). Build a Web Audio modular synthesizer and music
sequencer.

Using the Web Audio API implement: multiple oscillators (sine, square, triangle, sawtooth, noise),
ADSR envelopes, low/high/band-pass filters, LFO modulation, delay, distortion, a compressor, a
generated reverb impulse, master volume, and polyphonic playback with voice stealing.

Add a 16-step sequencer with at least eight tracks; each track has note or drum mode, mute/solo,
volume, pan, probability, velocity and per-step parameter locks. Add a piano keyboard, computer
keyboard input, tempo, swing, pattern length and chaining, a playback position and a metronome.

Provide oscilloscope and frequency-spectrum visualizers on canvas, a settled envelope preview and
an active-voice count. Generate drum sounds procedurally (no files). Add presets and save/load via
localStorage plus JSON import/export.

Audio must start only after a user interaction, with no clicks or unstable gain changes. The layout
fills the viewport and stays usable on smaller screens. Deliver the one file `website_app_signal_forge_{{MODEL_ID}}_01.html`.
