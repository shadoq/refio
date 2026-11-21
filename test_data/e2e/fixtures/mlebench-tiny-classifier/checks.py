import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import model  # noqa: E402

THRESHOLD = 0.90
TEST_LABELS = (
    "00100101110011000101100101000000000101100110110110111010111011000101011100010011"
    "01000001001011010101011101001110000101111000100011011010001110101101011010111111"
    "00011000100011011011100000100100011101000011100110001100101100000100100011001100"
    "11001110000000101011011001001001000000010111101000101010010000001001001110011101"
    "00000101110000110101111110111001001110001010111001011011110110100000100011011000"
)


def load(name, with_label):
    data = np.loadtxt(os.path.join(HERE, "data", name), delimiter=",", skiprows=1)
    if with_label:
        return data[:, :-1], data[:, -1].astype(int)
    return data


def main():
    X_train, y_train = load("train.csv", True)
    X_test = load("test_features.csv", False)
    y_test = np.array([int(c) for c in "".join(TEST_LABELS)])
    started = time.perf_counter()
    first = np.asarray(model.predict(model.fit(X_train.copy(), y_train.copy()), X_test.copy()))
    second = np.asarray(model.predict(model.fit(X_train.copy(), y_train.copy()), X_test.copy()))
    elapsed = time.perf_counter() - started
    if first.shape != (len(y_test),):
        print("predict must return one label per row, got shape", first.shape)
        return 1
    if not set(np.unique(first).tolist()) <= {0, 1}:
        print("labels must be 0 or 1")
        return 1
    if not np.array_equal(first, second):
        print("training is not deterministic: two runs gave different predictions")
        return 1
    if elapsed > 30:
        print("too slow: %.1fs" % elapsed)
        return 1
    accuracy = float((first == y_test).mean())
    print("accuracy %.3f (threshold %.2f)" % (accuracy, THRESHOLD))
    if accuracy < THRESHOLD:
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
