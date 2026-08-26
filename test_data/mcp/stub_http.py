#!/usr/bin/env python3
"""
MCP stub server over HTTP, for Refio's HTTP_STREAMABLE transport.

Two dialects, because Refio's client and the MCP specification disagree:

  --dialect refio (default)
      Answers a POST with a plain application/json JSON-RPC body. This is what
      MCPConnection.parseDirectResponse expects: it runs the body through
      gson.fromJson(raw, JsonObject).

  --dialect spec
      Answers a POST with an SSE-framed body (Content-Type: text/event-stream,
      "event: message" + "data: {...}"), and returns an Mcp-Session-Id header on initialize, which
      is what a specification-compliant Streamable HTTP server does.

Running a scenario against `spec` is how you demonstrate the gap: Refio cannot currently parse
that response. Keep the two dialects apart so a green test never silently means "our client agrees
with itself".

Usage:
  python stub_http.py --port 8931 --profile docs [--dialect spec]
"""
import argparse
import json
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mcp_core import PROFILES, StubServer

SESSION_HEADER = "Mcp-Session-Id"


def log(msg):
    sys.stderr.write("[stub_http] " + msg + "\n")
    sys.stderr.flush()


def build_handler(server, dialect, require_key=None, rate_limit_first=False):
    session_id = uuid.uuid4().hex
    state = {"served": 0}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self):
            if require_key and not self._authorized():
                # Mirrors the documented 401 for a missing or malformed key.
                self._send(401, b'{"error":"unauthorized","message":"Invalid API key"}',
                           "application/json")
                return

            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode("utf-8") if length else ""

            if rate_limit_first and state["served"] == 0 and self._method_of(raw) == "tools/call":
                # Mirrors the documented 429 with Retry-After. Only the first tool call is
                # throttled, so a client that retries can still make progress.
                state["served"] += 1
                self._send_throttled()
                return

            response = server.handle_raw(raw)

            if response is None:
                # A notification. There is nothing to return, and a body would confuse a client
                # that treats every POST as a request.
                self._send(202, b"", "application/json")
                return

            if dialect == "spec":
                body = ("event: message\ndata: %s\n\n" % response).encode("utf-8")
                self._send(200, body, "text/event-stream", with_session=self._is_initialize(raw))
            else:
                self._send(200, response.encode("utf-8"), "application/json")

        def do_GET(self):
            # Streamable HTTP allows a GET for server-initiated messages. Nothing to push here.
            self._send(405, b"", "text/plain")

        def _authorized(self):
            header = self.headers.get("Authorization") or ""
            return header == "Bearer %s" % require_key

        @staticmethod
        def _method_of(raw):
            try:
                return json.loads(raw).get("method")
            except ValueError:
                return None

        def _send_throttled(self):
            body = b'{"error":"rate_limited","message":"Too many requests"}'
            self.send_response(429)
            self.send_header("Content-Type", "application/json")
            self.send_header("Retry-After", "1")
            self.send_header("RateLimit-Limit", "10")
            self.send_header("RateLimit-Remaining", "0")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        @staticmethod
        def _is_initialize(raw):
            try:
                return json.loads(raw).get("method") == "initialize"
            except ValueError:
                return False

        def _send(self, status, body, content_type, with_session=False):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            if with_session:
                self.send_header(SESSION_HEADER, session_id)
            self.end_headers()
            if body:
                self.wfile.write(body)

        def log_message(self, fmt, *args):
            log(fmt % args)

    return Handler


def main():
    parser = argparse.ArgumentParser(description="MCP stub server over HTTP")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--profile", choices=PROFILES, default="docs")
    parser.add_argument("--dialect", choices=("refio", "spec"), default="refio")
    parser.add_argument(
        "--require-key",
        help="Reject requests whose Authorization header is not 'Bearer <key>' (401), like the real service."
    )
    parser.add_argument(
        "--rate-limit-first", action="store_true",
        help="Answer the first tools/call with 429 + Retry-After, like the real service under load."
    )
    args = parser.parse_args()

    stub = StubServer(profile=args.profile, log=log)
    handler = build_handler(stub, args.dialect, args.require_key, args.rate_limit_first)
    httpd = ThreadingHTTPServer((args.host, args.port), handler)
    log("listening on %s:%d; profile=%s dialect=%s auth=%s" % (
        args.host, args.port, args.profile, args.dialect, "required" if args.require_key else "none"))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
