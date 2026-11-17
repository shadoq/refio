#!/usr/bin/env python3
"""Mini Shop - a stdlib-only catalog server for the local e2e exercise.

Endpoints:
    GET /            - HTML landing page listing every product
    GET /catalog     - the machine-readable catalog (JSON, same file the shop serves)
    GET /product/<id> - one product page (HTML)
    GET /health      - readiness probe

This is a throwaway local target for the e2e harness and must NEVER be a real shop.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

os.chdir(os.path.dirname(os.path.abspath(__file__)))

with open("catalog.json", encoding="utf-8") as fh:
    CATALOG = json.load(fh)


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="text/html; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            rows = "".join(
                f"<li><a href='/product/{p['id']}'>{p['name']}</a> ({p['category']})</li>"
                for p in CATALOG["products"]
            )
            self._send(
                200,
                "<html><body><h1>Mini Shop</h1>"
                f"<ul>{rows}</ul>"
                "<p>Machine-readable catalog: <a href='/catalog'>/catalog</a></p>"
                "</body></html>",
            )
        elif path == "/catalog":
            self._send(200, json.dumps(CATALOG, indent=2), "application/json")
        elif path.startswith("/product/"):
            pid = path.split("/product/", 1)[1]
            product = next((p for p in CATALOG["products"] if p["id"] == pid), None)
            if product is None:
                self._send(404, "no such product")
            else:
                self._send(
                    200,
                    f"<html><body><h2>{product['name']}</h2>"
                    f"<p>category={product['category']} "
                    f"price={product['price']:.2f} rating={product['rating']}</p>"
                    "</body></html>",
                )
        elif path == "/health":
            self._send(200, "ok", "text/plain")
        else:
            self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8740
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
