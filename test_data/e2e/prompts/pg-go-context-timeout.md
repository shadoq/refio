`fetcher/fetcher.go` downloads the small documents the nightly jobs poll, using a plain
`http.Get` with no deadline at all. One hung origin is therefore enough to stall a job
forever, and the service embedding this package also wants to cancel in-flight fetches when
its own context is done.

Your task:

1. Add `FetchCtx(ctx context.Context, url string) ([]byte, error)` to `fetcher/fetcher.go`,
   building the request with `http.NewRequestWithContext` so the caller's cancellation and
   deadlines propagate into the HTTP exchange; keep reading the full (capped) body with
   `io.ReadAll` as before.
2. Turn `Fetch` into a thin wrapper with its existing signature: install the configurable
   default timeout with `context.WithTimeout`, release it with a deferred `cancel()`, and
   delegate to `FetchCtx`.
3. Map `context.DeadlineExceeded` (detected with `errors.Is`) to an exported typed error such
   as `ErrTimeout`, so callers can tell "too slow" apart from "unreachable".
4. Keep the status-code check, the `MaxBodyBytes` cap and `FetchText`'s behavior; the only
   new semantics are the deadline and the cancellation.

Do not modify `go.mod` or the README: the change belongs in `fetcher/fetcher.go`.
