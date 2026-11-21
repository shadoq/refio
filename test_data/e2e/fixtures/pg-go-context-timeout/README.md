# fetcher

`fetcher/fetcher.go` downloads the small documents the nightly jobs depend
on: status probes and price lists, all expected to answer within a second or
two. `Fetch` returns the raw body bytes, `FetchText` the same bytes decoded
as a string. Bodies are capped at `MaxBodyBytes`, and anything outside the
200 range is an error.

Module `github.com/acme/fetcher`, Go 1.21. There is no main package here -
the jobs import `fetcher` and call `fetcher.Fetch(url)`.
