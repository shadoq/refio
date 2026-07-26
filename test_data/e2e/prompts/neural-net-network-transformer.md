Write an interactive educational visualization in a single self-contained file
`network_transformer_model_01.html` (HTML + CSS + JavaScript inline; no external libraries or CDNs).

Explain how a Transformer neural network works by showing the flow of data for a typed sentence,
step by step: tokenization, embeddings, positional encoding, self-attention (with a live attention
matrix/heatmap), multi-head attention, feed-forward layers, residual connections, layer
normalization, and final output token probabilities.

Controls: a text input with preset example sentences, play, pause, previous step, next step, reset,
an animation speed slider, a simplified/advanced toggle, and an encoder-only / decoder-only /
encoder-decoder selector.

Layout: keep a persistent architecture diagram (tokens -> embeddings -> positional -> attention
blocks -> feed-forward -> normalization -> output head) visible next to the live step visualization
at all times; do not hide the diagram behind tabs. Show Q/K/V in an understandable way, an attention
heatmap that updates live, and beginner-friendly explanations of the current stage.

Deliver the one file `network_transformer_model_01.html`.
