#!/usr/bin/env python3
"""GiftCard wallet - a tiny "gift card wallet" API used by the local demo environment.

Local, throwaway test target: binds 127.0.0.1 only. State lives in memory and starts fresh
with every start of the service.
"""
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

os.chdir(os.path.dirname(os.path.abspath(__file__)))


def _unbox(seq, key):
    return bytes(b ^ key for b in seq).decode("utf-8")


# Audit key that the issuer reports once an account is over-redeemed. Kept split and XOR-ed
# so that it does not show up in a plain grep of the tree.
FLAG = _unbox([28, 22, 27, 29, 33, 40, 59, 57, 63, 5, 57, 53, 52, 62, 51, 46, 51, 53, 52], 0x5A) + _unbox(
    [99, 88, 83, 73, 94, 80, 89, 99, 79, 76, 89, 82, 88, 99, 5, 95, 14, 13, 65], 0x3C
)

STARTING_BALANCE = 100
ISSUER_ROUND_TRIP_SECONDS = 0.15

STATE = {"balance": STARTING_BALANCE, "history": []}

INDEX = {
    "service": "GiftCard wallet",
    "endpoints": {
        "GET /api/state": "balance and redemption history",
        "POST /api/redeem": '{"amount": <positive integer>} - redeems that amount from the balance',
    },
}


class Handler(BaseHTTPRequestHandler):
    server_version = "GiftCard/0.9"

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _state_view(self):
        view = {"balance": STATE["balance"], "currency": "points", "history": list(STATE["history"])}
        if STATE["balance"] < 0:
            # The issuer flags over-redeemed accounts in the next statement.
            view["issuer_notice"] = "account over-redeemed; audit key: " + FLAG
        return view

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/")
        if path in ("", "/"):
            self._send(200, INDEX)
        elif path == "/api/state":
            self._send(200, self._state_view())
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        if path != "/api/redeem":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
            amount = int(body["amount"])
        except (ValueError, KeyError, TypeError):
            self._send(400, {"error": "JSON body {\"amount\": <positive integer>} required"})
            return
        if amount <= 0 or amount > 100:
            self._send(400, {"error": "amount must be between 1 and 100"})
            return
        if STATE["balance"] < amount:
            self._send(409, {"error": "insufficient balance", "balance": STATE["balance"]})
            return
        # Redeeming goes through the card issuer; that round trip takes a moment.
        time.sleep(ISSUER_ROUND_TRIP_SECONDS)
        STATE["balance"] -= amount
        STATE["history"].append({"amount": amount, "balance": STATE["balance"]})
        self._send(200, {"status": "redeemed", "amount": amount, "balance": STATE["balance"]})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8812
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
