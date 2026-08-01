#!/usr/bin/env python3
"""
MCP stub server over SSE, for Refio's HTTP_SSE transport.

Two dialects, because Refio's client and the MCP specification disagree:

  --dialect refio (default)
      One URL. GET with Accept: text/event-stream opens a long-lived stream that only carries
      server-initiated notifications; POST to the same URL returns the JSON-RPC response in the
      body. This is what MCPHttpTransport.startSse plus MCPConnection.sendRequest implement.

  --dialect spec
      GET /sse first emits an `endpoint` event announcing a separate POST address; POST there is
      acknowledged with 202 and the actual response is delivered over the open stream. This is the
      specification's SSE transport.

Refio has no handling for the `endpoint` event and reads responses from the POST body, so the
`spec` dialect is expected to fail against the current client. That failure is the evidence, not a
broken fixture.

Usage:
  python stub_sse.py --port 8932 --profile docs [--dialect spec]
"""
import argparse
import json
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mcp_core import PROFILES, StubServer

PING_INTERVAL_SECONDS = 15


def log(msg):
    sys.stderr.write("[stub_sse] " + msg + "\n")
    sys.stderr.flush()


class StreamRegistry:
    """Open SSE streams, so the spec dialect can push a response to a client that POSTed."""

    def __init__(self):
        self._lock = threading.Lock()
        self._streams = []

    def add(self, wfile):
        with self._lock:
            self._streams.append(wfile)

    def remove(self, wfile):
        with self._lock:
            if wfile in self._streams:
                self._streams.remove(wfile)

    def broadcast(self, payload):
        frame = ("event: message\ndata: %s\n\n" % payload).encode("utf-8")
        with self._lock:
            targets = list(self._streams)
        delivered = 0
        for wfile in targets:
            try:
                wfile.write(frame)
                wfile.flush()
                delivered += 1
            except OSError:
                self.remove(wfile)
        return delivered


def build_handler(server, dialect, streams, endpoint_url):

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if dialect == "spec" and not self.path.startswith("/sse"):
                self._send(404, b"", "text/plain")
                return
            self._open_stream()

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode("utf-8") if length else ""
            response = server.handle_raw(raw)

            if dialect == "spec":
                # The response travels over the stream; the POST is only acknowledged.
                self._send(202, b"", "text/plain")
                if response is not None:
                    if streams.broadcast(response) == 0:
                        log("no open stream to deliver response to")
                return

            if response is None:
                self._send(202, b"", "application/json")
            else:
                self._send(200, response.encode("utf-8"), "application/json")

        def _open_stream(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            # No Content-Length: the body is open-ended and ends when the connection closes.
            self.send_header("Connection", "close")
            self.end_headers()

            streams.add(self.wfile)
            try:
                if dialect == "spec":
                    self.wfile.write(
                        ("event: endpoint\ndata: %s\n\n" % endpoint_url).encode("utf-8")
                    )
                    self.wfile.flush()
                while True:
                    time.sleep(PING_INTERVAL_SECONDS)
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
            except OSError:
                pass
            finally:
                streams.remove(self.wfile)

        def _send(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def log_message(self, fmt, *args):
            log(fmt % args)

    return Handler


def main():
    parser = argparse.ArgumentParser(description="MCP stub server over SSE")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--profile", choices=PROFILES, default="docs")
    parser.add_argument("--dialect", choices=("refio", "spec"), default="refio")
    args = parser.parse_args()

    stub = StubServer(profile=args.profile, log=log)
    streams = StreamRegistry()
    endpoint_url = "http://%s:%d/messages?sessionId=%s" % (args.host, args.port, uuid.uuid4().hex)
    handler = build_handler(stub, args.dialect, streams, endpoint_url)

    httpd = ThreadingHTTPServer((args.host, args.port), handler)
    httpd.daemon_threads = True
    log("listening on %s:%d; profile=%s dialect=%s" % (args.host, args.port, args.profile, args.dialect))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
