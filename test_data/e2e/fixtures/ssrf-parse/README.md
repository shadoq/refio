# LinkFetch

A small "safe URL fetcher" service used by the internal demo environment. It fetches a URL
on the caller's behalf and is supposed to talk only to the internal API and metadata
hosts; the link-local metadata address and anything under `.admin` must never be reached.

- `server/app.py` - the service (Python standard library only). `POST /fetch {"url": ...}`
  performs the fetch, `GET /` documents the endpoints. The demo environment's mock
  upstream services (api.internal, metadata.internal, the link-local metadata stub) run
  behind the same listener and are selected by the Host header, so the whole thing works
  with zero external network access.
- `tests/integration_test.py` - the integration suite. Run it with no arguments (it starts
  a fresh server on a free port itself) or against a live instance:
  `python3 tests/integration_test.py http://127.0.0.1:8846`.

The allowlist is enforced in `host_is_allowed()` and consulted by `Fetcher.fetch()` before
the first request goes out. Ops flagged the fetcher in a recent pentest as "reachable
bypass"; the integration suite encodes the behaviours they care about.
