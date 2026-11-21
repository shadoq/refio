`src/server.js` serves `GET /api/v1/users`, returning `{ id, name }`. Mobile clients we cannot update depend on that shape.

Plan adding `GET /api/v2/users` returning `{ id, firstName, lastName }`. The v1 response must stay exactly as it is.

New code goes in a new `src/v2.js`; new tests go under `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
