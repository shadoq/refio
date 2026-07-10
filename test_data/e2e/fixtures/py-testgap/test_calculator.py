from calculator import add, subtract


def test_add():
    assert add(2, 3) == 5


def test_subtract():
    assert subtract(5, 2) == 3

# Note: there is no test for divide - neither the happy path nor the
# divide-by-zero guard is exercised.
