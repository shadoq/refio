`src/utils/strings.js` is being renamed to `src/utils/text.js` (same exports). Several files import it.

Plan the rename. `node tests/run.js` must pass after every single step of the plan, so the project never has a broken import, and when the plan is done `src/utils/strings.js` no longer exists.

The only new file allowed is `src/utils/text.js`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
