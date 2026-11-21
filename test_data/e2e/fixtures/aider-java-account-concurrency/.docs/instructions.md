# Instructions

Implement `BankAccount` in `src/BankAccount.java`: an account that can be opened and closed, receives deposits and withdrawals in cents, and can be used from many threads at once. `InsufficientFundsException` is already written; keep it as it is.

## Lifecycle

- A new `BankAccount` is **closed**.
- `open()` opens it with a balance of 0. Opening an account that is already open throws `IllegalStateException`. A closed account can be opened again and starts from 0 again.
- `close()` closes an open account and returns the balance that was paid out (possibly 0). Closing an account that is not open throws `IllegalStateException`.

## Operations

All amounts are `long` cents.

- `balance()` returns the current balance.
- `deposit(amount)` adds to the balance. `withdraw(amount)` removes from it.
- `transferTo(other, amount)` moves money from this account to `other` in one atomic step.

On a closed account `balance`, `deposit`, `withdraw` and `close` throw `IllegalStateException`; this check comes before any check of the amount. For `transferTo` both accounts must be open, otherwise `IllegalStateException` and nothing changes.

An amount must be greater than 0, otherwise `IllegalArgumentException`. `transferTo` also throws `IllegalArgumentException` when `other` is the same account, and it checks its arguments before it looks at the state of either account.

Overdrafts are rejected: a `withdraw` or `transferTo` for more than the balance throws `InsufficientFundsException` and changes nothing. A `deposit` that would overflow `long` throws `ArithmeticException` and changes nothing.

## Concurrency

Every method is safe to call from any number of threads at the same time:

- no deposit or withdrawal is ever lost, and the balance never goes below 0 even when many threads withdraw at once;
- `close()` is atomic with deposits: every deposit that returned normally is part of the payout of a later `close()`, and a deposit that comes after the close fails;
- a transfer is atomic (no other thread sees the money gone from one account and not yet in the other), and transfers in opposite directions between the same two accounts must never deadlock.
