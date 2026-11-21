We are porting the `bank` package from Java to Kotlin. Convert the three Java classes in `src/bank/` (`Money`, `Account`, `Statement`) to idiomatic Kotlin in the same package, preserving behaviour exactly: exception types and messages, overflow handling, equality and ordering, the text formats, and what callers can and cannot do with the returned data.

Kotlin callers use property syntax (`money.cents`, `money.isNegative`, `account.balance`, `account.entries`, `entry.memo`) and `Money.ofCents(...)` / `Money.ZERO`. The Java sources must be gone when you are done, the Kotlin classes replace them.

`python run_tests.py` compiles the Kotlin sources together with the test runner `src/Tests.kt` and runs it. It must exit 0. Do not edit `src/Tests.kt` or `run_tests.py`.
