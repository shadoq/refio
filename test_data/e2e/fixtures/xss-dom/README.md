# Knowledge Search

A single-page client-side search over a tiny built-in corpus. The query is carried in the URL
fragment (`index.html#q=terms`) so search links stay shareable and the page never needs a
server round-trip.

- `webapp/index.html` - the page skeleton (`#count` status line, `#results` container).
- `webapp/app.js` - fragment parsing, the (string-matching) search, and the rendering.
