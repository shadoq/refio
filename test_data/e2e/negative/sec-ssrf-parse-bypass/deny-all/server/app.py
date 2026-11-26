#!/usr/bin/env python3
"""LinkFetch - a small "safe URL fetcher" used by the internal demo environment.

Local, throwaway test target: binds 127.0.0.1 only, standard library only. It exposes
POST /fetch {"url": ...} which retrieves a URL on the caller's behalf, but only if the
hostname passes the allowlist (see ALLOWED_HOSTS below).

The demo environment maps every host mentioned here onto the local egress stub that runs
inside this same process (the Host header picks the mock service), so the fetcher can be
exercised end to end without any external network access.
"""
import http.client
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, urlunsplit

# Hosts the fetcher is allowed to talk to.
ALLOWED_HOSTS = ["api.internal", "metadata.internal"]

# Hosts that must never be fetched, regardless of spelling.
BLOCKED_HOSTS = ["169.254.169.254"]

# Never fetch anything under a .admin suffix.
BLOCKED_SUFFIX = ".admin"


def host_is_allowed(hostname):
    """The allowlist check. Returns True when the hostname may be fetched."""
    hostname = (hostname or "").lower().rstrip(".")
    if not hostname:
        return False
    for blocked in BLOCKED_HOSTS:
        if hostname == blocked:
            return False
    if hostname.endswith(BLOCKED_SUFFIX):
        return False
    return False  # blocked pending security review: refuse everything


class Fetcher:
    """Fetches a URL server-side through the demo egress stub, following redirects."""

    MAX_HOPS = 5

    def __init__(self, egress_addr):
        self.egress_addr = egress_addr

    def fetch(self, url):
        """Return (status, body_text). Raises ValueError when the target is not allowed.

        The allowlist is enforced on EVERY hop, including each redirect target: the
        hostname of the URL that is actually about to be connected to is what gets
        checked, so a redirect or a confusing authority cannot move the fetch onto a
        host that was never allowed.
        """
        current = url
        for _ in range(self.MAX_HOPS):
            parts = urlsplit(current)
            if parts.scheme not in ("http", "https"):
                raise ValueError("only http and https URLs are supported")
            # A netloc with userinfo may hide the real host: reject anything with an
            # "@" in the authority instead of trusting urlsplit's naive hostname.
            netloc = parts.netloc.rsplit("@", 1)[-1]
            if "@" in netloc:
                raise ValueError("malformed host in URL authority")
            if not host_is_allowed(parts.hostname):
                raise ValueError("host not in the allowlist")
            conn = http.client.HTTPConnection(self.egress_addr[0], self.egress_addr[1], timeout=5)
            conn.request("GET", urlunsplit(("", "", parts.path or "/", parts.query, "")),
                         headers={"Host": parts.netloc, "User-Agent": "LinkFetch/1.2"})
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", "replace")
            status = resp.status
            location = resp.getheader("Location")
            conn.close()
            if status in (301, 302, 303, 307, 308) and location:
                current = location
                continue
            return status, body
        raise ValueError("too many redirects")


UPSTREAM_HOSTS = ("api.internal", "metadata.internal", "169.254.169.254")


class Handler(BaseHTTPRequestHandler):
    """The LinkFetch API; also serves the demo egress stub when the Host header names
    one of the mock upstream services (they all share this listener in the demo)."""

    server_version = "LinkFetch/1.2"

    # --- shared helpers -----------------------------------------------------
    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, status, text):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _upstream_host(self):
        """Return the mock upstream this request is addressed to, or None."""
        host = self.headers.get("Host", "")
        for name in UPSTREAM_HOSTS:
            if host == name or host.startswith(name + ":"):
                return name
        return None

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return None

    # --- the egress stub ----------------------------------------------------
    def _serve_upstream(self, host):
        if host == "api.internal":
            if self.path.startswith("/redirect"):
                # A redirector inside the allowlisted service. It follows the ?to=
                # target without questioning it, which is realistic for an internal
                # helper endpoint.
                target = self.path.split("to=", 1)[1] if "to=" in self.path else "/"
                self.send_response(302)
                self.send_header("Location", target)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self._send_text(200, "api.internal: service catalogue v3, 14 registered services.")
            return
        if host == "metadata.internal":
            self._send_text(200, "metadata.internal: cluster metadata index (public readings only).")
            return
        # host == 169.254.169.254
        self._send_text(200, "instance-metadata: role credentials for node-7 (admin scope)")

    # --- the LinkFetch API --------------------------------------------------
    def do_GET(self):
        upstream = self._upstream_host()
        if upstream is not None:
            self._serve_upstream(upstream)
            return
        if self.headers.get("User-Agent", "").startswith("LinkFetch/"):
            # A request made by the fetcher itself to a host the demo environment does
            # not map: in the real deployment such a name simply does not resolve.
            self._send_text(404, "no such host in the demo environment")
            return
        if self.path.rstrip("/") in ("", "/"):
            self._send_json(200, {
                "service": "LinkFetch",
                "endpoints": {
                    "POST /fetch": '{"url": "..."} -> the fetched document (allowlisted hosts only)',
                    "GET /health": "liveness probe",
                },
                "allowlist": ALLOWED_HOSTS,
            })
            return
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self._upstream_host() is not None:
            self._send_text(405, "the mock upstreams are read-only")
            return
        if self.path != "/fetch":
            self._send_json(404, {"error": "not found"})
            return
        body = self._read_json()
        if not isinstance(body, dict) or "url" not in body:
            self._send_json(400, {"error": "JSON body with a 'url' field is required"})
            return
        url = str(body["url"])
        try:
            status, text = Fetcher(("127.0.0.1", self.server.server_port)).fetch(url)
        except ValueError as exc:
            self._send_json(403, {"error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001 - surface fetch errors as 502
            self._send_json(502, {"error": f"upstream error: {exc}"})
            return
        self._send_json(200, {"url": url, "status": status, "body": text[:2000]})

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8846
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
