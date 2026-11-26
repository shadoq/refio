import argparse
import json
import sqlite3
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import attachments, config, db, export, tokens, views
from .auth import authenticate

ROUTES = {
    "/health": "health",
    "/notes": "notes",
    "/notes/search": "search",
    "/notes/export": "export",
    "/attachments": "attachments",
}


class Handler(BaseHTTPRequestHandler):
    server_version = "notes-api/" + config.VERSION

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        action = ROUTES.get(url.path)
        try:
            if action == "health":
                body, ctype, status = json.dumps({"status": "ok", "version": config.VERSION}), "application/json", 200
            elif action == "search":
                user = authenticate(self.headers)
                if not user:
                    body, ctype, status = views.render_error(401, "sign in first"), "text/html", 401
                else:
                    pattern = (query.get("q") or [""])[0]
                    hits = db.search_notes(user["id"], pattern)
                    body, ctype, status = views.render_results(hits), "text/html", 200
            elif action == "export":
                user = authenticate(self.headers)
                if not user:
                    body, ctype, status = views.render_error(401, "sign in first"), "text/html", 401
                else:
                    fmt = (query.get("format") or ["pdf"])[0]
                    body, ctype, status = export.export_notes(user["name"], fmt), "application/octet-stream", 200
            elif action == "attachments":
                name = (query.get("name") or [""])[0]
                body, ctype, status = attachments.fetch_attachment(name)
            else:
                body, ctype, status = views.render_error(404, "no such page"), "text/html", 404
        except Exception as exc:  # keep the worker alive; the log keeps the traceback
            print("request failed: %r" % (exc,), file=sys.stderr)
            body, ctype, status = views.render_error(500, "something went wrong"), "text/html", 500
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="notes_api")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--db", default="notes.sqlite3")
    args = parser.parse_args(argv)
    settings = config.load_settings()
    db.connect(args.db)
    httpd = ThreadingHTTPServer((settings["bind_address"], args.port), Handler)
    print("notes-api %s listening on %s:%d" % (config.VERSION, settings["bind_address"], args.port))
    httpd.serve_forever()


if __name__ == "__main__":
    main()
