`lib/http-legacy.js` offers `getJSON(url, callback)`; `lib/http.js` offers the same as `getJSON(url)` returning a promise. Three modules still use the legacy one.

Plan migrating every caller to `lib/http.js` and then deleting `lib/http-legacy.js`. Each step must leave `node tests/run.js` passing.

New files may go only under `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
