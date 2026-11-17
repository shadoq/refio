`lib/order.rb` is the storefront's Order model, and it shows its age: `initialize` takes
five positional arguments, the status is a bare string compared with `== "pending"` (and
its siblings) in several places, and nothing is frozen, so a typo'd status sticks
silently. The team wants idiomatic modern Ruby without changing what the model does.

Your task:

1. Convert `initialize` in `lib/order.rb` to keyword arguments with sensible defaults
   (`items: []`, `status: :pending`, `note: nil`), normalizing the status with `to_sym` so
   string and symbol callers both work.
2. Add a frozen `STATUSES` tuple (`%i[pending paid shipped].freeze`) and the predicate
   methods `pending?`, `paid?` and `shipped?` - comparing against symbols, never against
   the magic strings.
3. Freeze instances where it is safe: an order that reaches its terminal state should be
   frozen, so any later mutation fails loudly instead of sticking silently.
4. Keep the line-item math (`total_cents`, `item_count`), `summary` and `to_s` behavior
   intact; only the construction and status-handling style changes.

Do not modify the README: the change belongs in `lib/order.rb`.
