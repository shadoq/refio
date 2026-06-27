`applyDiscount` in `src/discount.js` currently hardcodes a 10% discount. Make the discount rate
configurable:

1. Add a second parameter `rate` (a fraction such as `0.25` for 25%) to `applyDiscount` and use it
   instead of the hardcoded `0.1`.
2. Find every caller of `applyDiscount` across the project and update each one to pass an explicit
   rate. The existing callers must preserve their current 10% behavior (pass `0.1`). There is more
   than one caller, and they live in more than one file - find them all.

Do NOT change `test.js` or weaken the assertions. When you are done, `node test.js` must print `OK`.
