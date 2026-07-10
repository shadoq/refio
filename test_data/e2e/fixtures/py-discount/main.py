from src.discount import discounted_total

assert discounted_total(50) == 50, discounted_total(50)
assert discounted_total(100) == 90.0, discounted_total(100)
assert discounted_total(200) == 180.0, discounted_total(200)
print("OK")
