## Signup validators accept garbage emails and impossible dates

### Symptom

The CSV import queued a batch of signups that never should have passed
validation: addresses like `a@b@c` and `user@example.com<script>` sailed
through, and birth dates like `13/01/2023` and `01/32/2023` were accepted (one
participant is apparently born on the 32nd). Support also complains that paper
forms written as `1/2/2023` (single-digit month and day) are rejected, which
the clerks say used to work on the old form handler.

### How to reproduce

    node -e '
    const { isValidEmail, isValidDate } = require("./validators");
    console.log(isValidEmail("a@b@c"));       // true  - must be false
    console.log(isValidDate("13/01/2023"));   // true  - must be false
    console.log(isValidDate("1/2/2023"));     // false - must be true
    '

### Expected behaviour

`isValidEmail` must match the whole input: one local part, one `@`, and a
domain ending in a dot-TLD of at least two letters - nothing before, after or
in between. `isValidDate` must match MM/DD/YYYY with an optional leading zero,
a real month (01-12) and a real day (01-31), and nothing else around it. The
slug and hex-colour validators are fine and must keep working exactly as they
do now.

### Your task

1. Reproduce the three cases above in `validators.js` and look at how
   `EMAIL_PATTERN` and `DATE_PATTERN` are declared.
2. Fix the two pattern declarations in `validators.js` in place: anchor both at
   the string boundaries and constrain the month/day ranges, keeping the
   validator functions, `validateSignup` and the exports unchanged.
3. Run the gate: `node checks.js` - it replays the valid and invalid tables
   from the issue plus the untouched validators and the aggregated signup
   check, and must pass.

Do not modify `checks.js` or the README: the fix belongs in `validators.js`.
