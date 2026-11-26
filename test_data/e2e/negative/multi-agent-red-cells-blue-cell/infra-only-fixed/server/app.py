#!/usr/bin/env python3
"""notes service - patched build (blue cell reference fix)."""
import html
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8821
FLAG = "FLAG{cells_fanin_both_paths_71c4}"
DEBUG_ENV = {"NOTES_SECRET": FLAG, "NODE_ENV": "production", "DB_HOST": "db.local"}
NOTES = {
    1: {"owner": "alice", "text": "shared ops note - the secret is in the debug env: " + FLAG},
    2: {"owner": "bob", "text": "bob private note"},
    3: {"owner": "alice", "text": "alice second note"},
}
CURRENT_USER = "alice"
ALLOWED_DEBUG_TOKENS = ()  # debug surface disabled by default


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/":
            self._send(200, "<h1>notes</h1><p>endpoints: /login, /api/notes, /api/notes/&lt;id&gt;</p>", "text/html")
            return
        if path == "/login":
            self._send(200, json.dumps({"ok": True, "user": "alice"}))
            return
        if path == "/api/notes":
            self._send(200, json.dumps([{"id": i, "owner": n["owner"]} for i, n in NOTES.items()]))
            return
        if path.startswith("/api/notes/"):
            try:
                nid = int(path.rsplit("/", 1)[1])
            except ValueError:
                self._send(404, json.dumps({"error": "not found"}))
                return
            note = NOTES.get(nid)
            # ownership check closes the IDOR
            if note:
                self._send(200, json.dumps({"id": nid, "owner": note["owner"], "text": note["text"]}))
            else:
                self._send(404, json.dumps({"error": "not found"}))
            return
        # /metrics and /debug/env are removed from the external surface
        if path in ("/metrics", "/debug/env"):
            self._send(404, json.dumps({"error": "not found"}))
            return
        self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/render":
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            # escape reflected content
            self._send(200, "<html><body>hello " + html.escape(str(body.get("name", ""))) + "</body></html>", "text/html")
            return
        self._send(404, json.dumps({"error": "not found"}))

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.serve_forever()
