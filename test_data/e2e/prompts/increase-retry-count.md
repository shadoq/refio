Requests in this project give up far too easily — they currently retry only once, so a
transient failure that clears on the third try is reported as a permanent `GIVEUP`.

Increase the retry count to **5**. The retry count is a single named constant defined
somewhere in this project — find it and change **only its value**. Do not modify the retry
loop in `HttpClient`, do not touch any other constant, and do not edit `main` or unrelated code.
