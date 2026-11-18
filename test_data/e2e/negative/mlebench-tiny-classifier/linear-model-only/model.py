"""Binary classifier for data/train.csv (features x1..x4, label 0/1). numpy only."""

import numpy as np


def fit(X, y):
    """Train on X (n, 4 floats) and y (n ints in {0, 1}); return any object that predict() accepts."""
    mean = X.mean(axis=0)
    std = X.std(axis=0) + 1e-9
    Z = (X - mean) / std
    w = np.zeros(Z.shape[1])
    b = 0.0
    for _ in range(3000):
        p = 1.0 / (1.0 + np.exp(-(Z @ w + b)))
        g = p - y
        w -= 0.1 * (Z.T @ g / len(y) + 0.01 * w)
        b -= 0.1 * g.mean()
    return {"mean": mean, "std": std, "w": w, "b": b}


def predict(model, X):
    """Return an int array of 0/1 labels, one per row of X."""
    Z = (X - model["mean"]) / model["std"]
    return ((Z @ model["w"] + model["b"]) > 0).astype(int)
