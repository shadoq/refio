`place` and `update` in `src/Orders.kt` contain the same two validation checks, duplicated.
Extract them into a single `private fun validate(qty: Int, price: Int)` and call `validate(qty, price)`
at the start of both functions. Afterwards the duplicated `if` checks must exist in exactly one place.
