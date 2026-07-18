from src.discount import discounted_total
from src.shipping import shipping_fee

# Discount must apply at exactly 100, not only above it.
assert discounted_total(100) == 90.0, discounted_total(100)
assert discounted_total(99) == 99, discounted_total(99)
assert discounted_total(200) == 180.0, discounted_total(200)

# Shipping must be free at exactly 50 kg, not only above it.
assert shipping_fee(50) == 0, shipping_fee(50)
assert shipping_fee(60) == 0, shipping_fee(60)
assert shipping_fee(49) == 15, shipping_fee(49)

print("OK")
