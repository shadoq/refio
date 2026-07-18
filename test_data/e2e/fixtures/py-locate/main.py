from src.total import compute_total

# compute_total must sum every element, including the last one.
assert compute_total([10, 20, 30]) == 60, compute_total([10, 20, 30])
assert compute_total([]) == 0, compute_total([])
assert compute_total([5]) == 5, compute_total([5])

print("OK")
