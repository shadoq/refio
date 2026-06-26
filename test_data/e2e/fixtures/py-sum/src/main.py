def total(n):
    # BUG: range(1, n) stops at n-1, so the upper bound n is excluded.
    return sum(range(1, n))


if __name__ == "__main__":
    assert total(5) == 15, f"total(5) was {total(5)}, expected 15"
    assert total(1) == 1, f"total(1) was {total(1)}, expected 1"
    print("OK")
