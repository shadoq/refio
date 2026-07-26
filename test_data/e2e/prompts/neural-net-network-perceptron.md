Write an interactive neural network visualization in a single self-contained file
`network_perceptron_model_01.html` (HTML + CSS + vanilla JavaScript inline; no external libraries).

Build a visual demo of a single-layer perceptron that classifies 2D points on a coordinate plane.
Show input points from two classes with different colors. Display the perceptron as a neuron with
weighted inputs, bias, and output. Visualize the decision boundary line updating during training.
Add controls to start, pause, reset, and step through training one iteration at a time. Show
current weights, bias, learning rate, epoch number, and classification accuracy. Allow generating
random datasets and switching between linearly separable and non-separable data. Animate how the
weights change after each mistake. Add a small panel explaining why a single-layer perceptron
cannot solve XOR.

Layout: top toolbar (title, dataset preset, start/pause/step/reset, randomize); left config panel;
center workspace split into two permanently visible sections - left the 2D point plane with the
decision boundary, right the perceptron diagram (inputs, weights, bias, output); right diagnostics
panel; bottom analysis (accuracy history, mistakes, log). The plane and the schematic must stay
visible at the same time.

Deliver the one file `network_perceptron_model_01.html`.
