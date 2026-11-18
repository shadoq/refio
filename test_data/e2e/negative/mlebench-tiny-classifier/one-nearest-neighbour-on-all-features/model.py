"""Binary classifier for data/train.csv (features x1..x4, label 0/1). numpy only."""

import numpy as np


def fit(X, y):
    """Train on X (n, 4 floats) and y (n ints in {0, 1}); return any object that predict() accepts."""
    return {"X": X.copy(), "y": y.copy()}


def predict(model, X):
    """Return an int array of 0/1 labels, one per row of X."""
    out = np.empty(len(X), dtype=int)
    for i, row in enumerate(X):
        distances = ((model["X"] - row) ** 2).sum(axis=1)
        out[i] = model["y"][int(distances.argmin())]
    return out
