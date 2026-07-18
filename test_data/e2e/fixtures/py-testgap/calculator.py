"""A tiny arithmetic library."""


def add(a, b):
    return a + b


def subtract(a, b):
    return a - b


def divide(a, b):
    # Guards against division by zero by raising a clear error. This guard is the
    # behavior most in need of a test - and it currently has none.
    if b == 0:
        raise ValueError("cannot divide by zero")
    return a / b
