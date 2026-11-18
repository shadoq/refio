"""Binary classifier for data/train.csv (features x1..x4, label 0/1). numpy only."""

import numpy as np


def fit(X, y):
    """Train on X (n, 4 floats) and y (n ints in {0, 1}); return any object that predict() accepts."""
    # baseline: always answer with the most common training label
    return {"label": int(np.bincount(y).argmax())}


def predict(model, X):
    """Return an int array of 0/1 labels, one per row of X."""
    return np.full(len(X), model["label"], dtype=int)
