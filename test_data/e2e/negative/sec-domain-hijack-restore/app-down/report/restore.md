# Domain hijack restore: cleanup report

The gateway's interception layer was capturing api.example.test and answering it with
the hijacker's maintenance-portal page (marker hijacker-wins-7f3d), so clients of that host lost both
the real API and every security header the backend sets; the proxy copy dropped those
headers too.

## What was done

- Removed the interception layer and the hijacker's page from server/app.py:
  api.example.test is no longer captured and its requests reach the backend.
- Stopped dropping the backend's security headers on the proxied responses: all three
  are back with the exact values the backend sets.
- Unknown hosts still get the proxy's plain 404; the gateway and its backend routes are
  unchanged.

intercepted_host = api.example.test

stripped_headers_count = 3

proxy_rule = api.example.test is forwarded to the backend with its security headers kept
