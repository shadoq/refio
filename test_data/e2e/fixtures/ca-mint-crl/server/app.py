#!/usr/bin/env python3
"""PKIGate - a small TLS gateway of the demo environment.

Local, throwaway test target: binds 127.0.0.1 only, Python standard library only, TLS 1.3
via the ssl module. Client certificates are REQUIRED and checked against pki/ca.crt and,
when crl/crl.pem exists, against that CRL (no CRL file = no revocation check yet).
"""
import os
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def build_ssl_context():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.maximum_version = ssl.TLSVersion.TLSv1_3
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_cert_chain(os.path.join(ROOT, "pki/server.crt"),
                        os.path.join(ROOT, "pki/server.key"))
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.load_verify_locations(os.path.join(ROOT, "pki/ca.crt"))
    crl_path = os.path.join(ROOT, "crl/crl.pem")
    if os.path.isfile(crl_path):
        # Python's stdlib cannot parse a CRL; do the check at the application layer.
        import hashlib
        with open(crl_path, "rb") as fh:
            crl_blob = fh.read()
        return ctx, crl_path, hashlib.sha256(crl_blob).hexdigest()
    return ctx, None, None


SSL_CTX, CRL_PATH, CRL_SHA = build_ssl_context()


def revoked_serials():
    """Serials listed in crl/crl.pem, as uppercase hex (openssl x509 -crln no -text form).

    Uses openssl when available (the fixture documents that openssl is on PATH); if the
    openssl call fails for any reason the CRL is treated as unreadable and /admin is
    closed, which is the safe direction.
    """
    import subprocess
    serials = set()
    try:
        out = subprocess.run(
            ["openssl", "crl", "-in", CRL_PATH, "-noout", "-text"],
            capture_output=True, text=True, timeout=30, check=True).stdout
    except Exception:
        return None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Serial Number:"):
            serials.add(line.split(":", 1)[1].strip().replace(":", "").upper())
    return serials


class Handler(BaseHTTPRequestHandler):
    server_version = "PKIGate/2.0"

    def _send(self, status, text):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _client_serial(self):
        cert = self.connection.getpeercert()
        if not cert:
            return None
        for item in cert.get("serialNumber", "").split(":"):
            if item.strip():
                return item.strip().upper()
        return None

    def do_GET(self):
        if self.path == "/":
            self._send(200, "<html><body><h1>PKIGate</h1>"
                             "<p>TLS gateway of the demo environment.</p></body></html>")
            return
        if self.path.startswith("/admin"):
            if CRL_PATH is None:
                self._send(403, "<html><body><h1>403 no CRL</h1>"
                                "<p>Admin console disabled until a CRL is published.</p>"
                                "</body></html>")
                return
            serial = self._client_serial()
            revoked = revoked_serials()
            if revoked is None:
                self._send(403, "<html><body><h1>403 CRL unreadable</h1></body></html>")
                return
            if serial is not None and serial in revoked:
                self._send(403, "<html><body><h1>403 revoked</h1>"
                                "<p>Client certificate has been revoked.</p></body></html>")
                return
            self._send(200, "<html><body><h1>PKIGate admin</h1>"
                             "<p>Admin console; certificate accepted.</p></body></html>")
            return
        self._send(404, "<html><body><h1>404</h1></body></html>")

    def log_message(self, *args):
        pass

    def log_error(self, fmt, *args):
        # handshake failures from probes without a client cert are expected noise
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8851
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.socket = SSL_CTX.wrap_socket(httpd.socket, server_side=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
