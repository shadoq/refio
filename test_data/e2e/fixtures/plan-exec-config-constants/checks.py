"""Harness-owned check; the agent must not edit this file."""
import pathlib

from app import config
from app.shipping import shipping_cost
from app.tax import with_tax

assert config.FREE_SHIPPING_THRESHOLD == 100
assert config.SHIPPING_FEE == 9.99
assert config.TAX_RATE == 0.23
assert shipping_cost(150) == 0 and shipping_cost(50) == 9.99
assert with_tax(100) == 123.0
for name in ("shipping.py", "tax.py"):
    src = pathlib.Path("app", name).read_text()
    assert "config" in src, f"app/{name} must read its numbers from app/config.py"
    for magic in ("0.23", "9.99", "100"):
        assert magic not in src, f"app/{name} still hard-codes {magic}"
print("ok")
