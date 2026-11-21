"""Binary classifier for data/train.csv (features x1..x4, label 0/1). numpy only."""

import numpy as np


def _features(X):
    columns = [X]
    n = X.shape[1]
    for i in range(n):
        for j in range(i + 1, n):
            columns.append((X[:, i] * X[:, j])[:, None])
    return np.hstack(columns)


def fit(X, y):
    """Train on X (n, 4 floats) and y (n ints in {0, 1}); return any object that predict() accepts."""
    phi = _features(X)
    mean = phi.mean(axis=0)
    std = phi.std(axis=0) + 1e-9
    Z = (phi - mean) / std
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
    Z = (_features(X) - model["mean"]) / model["std"]
    return ((Z @ model["w"] + model["b"]) > 0).astype(int)
