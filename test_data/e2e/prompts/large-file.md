The `Ledger.totalBalance(credits, debits)` function in `src/Ledger.kt` is wrong.

It is supposed to return the running account balance as the **sum** of `credits` and `debits`, but
it currently subtracts them. Find `totalBalance` in the file and fix it so it returns their sum.

Change nothing else in the file - the many `feeTier*` and `share*` helpers around it are correct and
must stay byte-identical.
