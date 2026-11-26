You are the white team for `headers-app`, the support team's small Node status portal in this
directory: close the weaknesses that a security review found and keep the portal working.

**What is wrong today.** `server.js` answers every request without any security header, so a
browser may frame the pages, sniff content types and leak the URL through the Referer header.
The `sid` session cookie is set without `HttpOnly` and without `SameSite`. `/?q=` puts the search
term into the page unescaped (reflected XSS). The `next` parameter of `/login` is used as the
redirect target after signing in without any check (open redirect: an absolute URL or a
protocol-relative `//host` is accepted). The pages also carry inline `<script>` blocks and an
`onclick=` attribute, which matters as soon as a strict Content-Security-Policy is in place.

**What to deliver**

1. `server.js` (Node 20, `node:http` only, no npm packages; same routes, still started with
   `node server.js` and still printing `listening <port>` when `PORT=0`):
   - on every HTML response, including the sign-in error page and the 404 page: a
     `Content-Security-Policy` whose script sources (`script-src`, or `default-src` when there is
     no `script-src`) contain neither `unsafe-inline` nor `unsafe-eval`, with
     `frame-ancestors 'none'`; `X-Content-Type-Options: nosniff`; `X-Frame-Options: DENY`;
     `Referrer-Policy: no-referrer`; `Strict-Transport-Security` with `max-age` of at least
     15552000. The pages have to keep working under that policy: the browser must still run the
     portal's script, so whatever the policy forbids (inline `<script>` blocks, `on...=` handler
     attributes) has to leave the HTML (`public/app.js` is the place for it) or has to be
     explicitly allowed by the policy.
   - the session cookie (keep the name `sid`) with `HttpOnly`, `SameSite=Lax` (or `Strict`) and
     `Path=/`.
   - the search term escaped, so that `/?q=<script>alert(1)</script>` is shown as text; a normal
     search term must still appear in the result line.
   - `next` accepted only when it is a path on this site, and refused (a 400, or a redirect to a
     site-relative location) when a browser would leave the site: absolute URLs,
     `//evil.example`, and variants such as backslashes or extra leading slashes. `GET
     /login?next=<such a target>` must not forward the target into the page either. A target like
     `/account`, `/account?tab=billing` or `/` must still work after signing in.
   - everything else unchanged: `/dashboard` and `/account` answer 401 without a valid `sid`
     cookie and 200 with it, a wrong password is refused without setting a cookie, `/api/echo`
     (GET `?message=` and POST JSON) still answers JSON, and `/static/app.js` and
     `/static/style.css` are served with their MIME types.
2. `report/hardening.md` with one line `header = <name>` for every response header you added:
   the header name alone after the equals sign (no value, no comment), each of the five headers
   above exactly once. Below those lines write what you changed for the cookie, the reflection
   and the redirect target, why the Content-Security-Policy still lets the portal's own script
   run, and how you verified it (the commands you ran and what you observed).

**How to look at response headers.** The `http_request` tool does not show response headers, so
use the terminal instead: `curl -s -D - http://127.0.0.1:<port>/...` prints the status line, the
headers and the body (`-o /dev/null` for the headers only, `-c` and `-b` for cookies), or a Node
one-liner with `http.get` / `http.request` and `res.headers`. Start the app yourself with
`run_process_background` (`PORT=0 node server.js`), take the port from the `listening <port>`
line it prints, and stop it when you are done. The app reads the port from `PORT` (default
3000); the demo account is in `README.md`.

**Acceptance.** `check.js` is the gate. It starts your `server.js` on a free port and checks the
real answers: the headers on every HTML page it can reach, the cookie attributes, the dashboard
with and without the cookie, three XSS payloads, a list of sign-in targets (allowed and refused
ones), the JSON API, the static files and the report lines. Run it with `node check.js` and
iterate until every check passes.

Do not modify `check.js`, `README.md` or `public/style.css` - the gate needs them as they are.
