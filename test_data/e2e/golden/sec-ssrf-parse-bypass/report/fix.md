# SSRF allowlist bypass in LinkFetch: fix report

## Vulnerable code path

`server/app.py`, `Fetcher.fetch()`. The method validated the hostname of the caller
supplied URL exactly once, before the first request, and then followed up to five
redirects without ever re-checking the destination:

- the initial `urlsplit(url)` / `host_is_allowed(parts.hostname)` check ran only on the
  first hop;
- each `Location` header was assigned straight to `current` and re-requested with no
  validation.

## Root cause

The allowlist decision was made from the parsed hostname of the original URL, not from
the hostname of each connection the fetcher actually opens. Any allowlisted host that
offers a redirect (here `http://api.internal/redirect?to=...`) can therefore pivot the
fetch onto a blocked destination: `http://api.internal/redirect?to=http://169.254.169.254/`
returned 200 with the instance metadata body. The same one-shot parse is also what makes
confusing authority spellings such as `http://user@api.internal@169.254.169.254/`
dangerous: a naive parse can attribute the wrong host to the URL.

## Fix

The scheme check, the userinfo sanity check and `host_is_allowed()` now run inside the
redirect loop, on the URL of every hop that is about to be connected to, and the initial
pre-loop validation was removed (the first iteration of the loop performs it). Redirects
onto `169.254.169.254` and onto `.admin` names are now refused with 403, the userinfo
form is refused as a malformed authority, and legitimate `api.internal` /
`metadata.internal` fetches keep working.

`python3 tests/integration_test.py` passes: 8 checks (legit fetches, direct block,
redirect block, userinfo block, .admin blocks).
