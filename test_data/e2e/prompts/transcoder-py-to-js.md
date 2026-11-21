We are moving the report generator off Python. Translate `textstats.py` to JavaScript as `textstats.js` (CommonJS, no dependencies, same function names, same behaviour including edge cases and error messages).

`node --test textstats.test.js` must pass. Leave `textstats.py` and the test file as they are.
