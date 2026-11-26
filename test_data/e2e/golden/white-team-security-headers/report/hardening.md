# Hardening report: headers-app

Scope of this pass: `server.js` and `public/app.js`. The acceptance check `node check.js` passes
with every check green.

## Response headers added

header = Content-Security-Policy
header = X-Content-Type-Options
header = X-Frame-Options
header = Referrer-Policy
header = Strict-Transport-Security

All five are sent on every response, not only on pages: redirects, the 401 and 404 pages, the
JSON API and the static files get the same block.

- `Content-Security-Policy`: `default-src 'self'; script-src 'self'; style-src 'self'; img-src
  'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self';
  frame-ancestors 'none'`. Scripts may only come from this site, no `unsafe-inline` and no
  `unsafe-eval`.
- `X-Content-Type-Options: nosniff`: browsers take the declared content type, so a script or
  style is never sniffed out of another file.
- `X-Frame-Options: DENY` together with `frame-ancestors 'none'`: the portal cannot be embedded
  in another site (clickjacking).
- `Referrer-Policy: no-referrer`: the search term and the other URL parts are not leaked through
  the Referer header.
- `Strict-Transport-Security: max-age=31536000`: browsers keep to HTTPS for this host for a year.
  It only has an effect once the portal is reached over HTTPS.

## Why the portal still works under the policy

The policy forbids inline script. Before this pass every page carried an inline `<script>` block
(focus the search box, focus the user name, load the dashboard notes) and the dashboard had an
`onclick=` attribute. All of that moved into `public/app.js`: each page now says which page it is
through `<body data-page="...">` and loads `/static/app.js` with `defer`; the refresh button gets
its handler with `addEventListener`. `check.js` confirms that no HTML page contains a script
block without `src` or an event handler attribute, and that `/static/app.js` is served as
JavaScript (with `nosniff` the MIME type has to be right).

## Session cookie

`Set-Cookie: sid=...; HttpOnly; SameSite=Lax; Path=/; Max-Age=28800`. `HttpOnly` keeps the cookie
away from script, `SameSite=Lax` stops other sites from sending the cookie with their requests,
`Path=/` and `Max-Age` bound the session to the portal and to a working day. The name `sid` is
unchanged so that existing bookmarks and the reverse proxy rules keep working. A failed sign-in
sets no cookie.

## Reflected search term

`/?q=` is now HTML-escaped before it goes into the page (attribute and text), both in the input
field and in the result line. `<script>alert(1)</script>` is shown as text; a normal search term
is displayed as before.

## Sign-in target (`next`)

`next` is accepted only when it is a path on this site: it has to start with exactly one slash
and it is refused when it starts with `//` or `/\`, contains a backslash or a control character,
or carries a scheme. The value is decoded once before the test, so `%2F%2F` cannot hide a second
slash. A bad target answers 400 and the sign-in page drops it instead of putting it into the
form. `/account`, `/account?tab=billing` and `/` still work after signing in.

## Verified

- `PORT=0 node server.js`, then `curl -s -D - http://127.0.0.1:<port>/`, `/login`, `/dashboard`
  (without and with the cookie from `curl -c`): every HTML answer carries the five headers.
- `curl -s -D - 'http://127.0.0.1:<port>/?q=%3Cscript%3Ealert(1)%3C%2Fscript%3E'`: the term
  appears as `&lt;script&gt;`.
- `POST /login` with `next=//evil.example`, `/\evil.example`, `https://evil.example`: 400; with
  `next=/account`: 303 to `/account`.
- `POST /api/echo` with JSON: `application/json`, message echoed.
- `node check.js`: all checks passed.

## Follow-ups (not part of this pass)

- Add `Secure` to the cookie once the reverse proxy in front of the portal terminates TLS for
  every client; over plain HTTP on the intranet it would make sign-in impossible.
- The session id is still the user name. Replace it with a random id and a server-side session
  table in the next sprint.
