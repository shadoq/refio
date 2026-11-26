#!/usr/bin/env python3
"""CorpShop gateway: TLS endpoint of the demo environment.

Local, throwaway test target: binds 127.0.0.1 only, Python standard library only. The
certificate it serves is whatever the PROXY CONFIG points at; during the emergency
migration the gateway keeps serving while the operator swaps the PKI underneath it
(read PROXY CERT_PATH / KEY_PATH below: they mirror proxy/gw.conf).
"""
import os
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Mirrors proxy/gw.conf (cert / key): re-read at every boot.
CONF = {}
with open(os.path.join(ROOT, "proxy/gw.conf"), encoding="utf-8") as fh:
    for line in fh:
        if "=" in line:
            key, value = line.split("=", 1)
            CONF[key.strip()] = value.strip()


def build_ssl_context():
    cert = os.path.join(ROOT, CONF["cert"])
    key = os.path.join(ROOT, CONF["key"])
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.maximum_version = ssl.TLSVersion.TLSv1_3
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_cert_chain(cert, key)
    return ctx


class Handler(BaseHTTPRequestHandler):
    server_version = "CorpShopGW/4.2"

    def _send(self, status, text):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send(200, "<html><body><h1>CorpShop gateway</h1>"
                         "<p>Serving on the certificate configured in proxy/gw.conf.</p>"
                         "</body></html>")

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8853
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.socket = build_ssl_context().wrap_socket(httpd.socket, server_side=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
