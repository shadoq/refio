// Package fetcher downloads small documents over HTTP for the nightly jobs.
//
// The jobs poll status endpoints and fetch price lists that are expected to
// answer within a second or two. The package predates the team's deadline
// rules, so it currently has no opinion about how long a request may take.
package fetcher

import (
	"fmt"
	"io"
	"net/http"
)

// MaxBodyBytes caps how much of a response we are willing to buffer.
const MaxBodyBytes = 4 << 20 // 4 MiB

// Fetch retrieves url and returns the response body as bytes.
//
// Status codes outside the 200 range are reported as errors; the body of an
// error response is drained but not returned.
func Fetch(url string) ([]byte, error) {
	resp, err := http.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fetch %s: unexpected status %d", url, resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxBodyBytes))
	if err != nil {
		return nil, err
	}
	return body, nil
}

// FetchText retrieves url and returns the body decoded as a string.
//
// Every document we poll is UTF-8 text; binary payloads are not expected.
func FetchText(url string) (string, error) {
	body, err := Fetch(url)
	if err != nil {
		return "", err
	}
	return string(body), nil
}
