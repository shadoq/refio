#!/usr/bin/env python3
"""
MCP stub server over stdio.

Transport: newline-delimited JSON-RPC 2.0 (one JSON object per line), matching Refio's
MCPStdioTransport, which writes msg + "\\n" and reads by line. Stdlib only, works offline.

The server logic lives in mcp_core.py and is shared with the HTTP and SSE frontends. The default
profile is `legacy` (echo_marker + the project-notes resource), which is what manual tests T52-T58
expect; pass --profile to emulate another kind of provider.

Run standalone for a smoke check:
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize"}' | python stub_server.py
"""
import argparse
import sys

from mcp_core import PROFILES, StubServer


def log(msg):
    sys.stderr.write("[stub_server] " + msg + "\n")
    sys.stderr.flush()


def main():
    parser = argparse.ArgumentParser(description="MCP stub server over stdio")
    parser.add_argument("--profile", choices=PROFILES, default="legacy")
    args = parser.parse_args()

    server = StubServer(profile=args.profile, log=log)
    log("started; profile=%s" % args.profile)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        response = server.handle_raw(line)
        if response is not None:
            sys.stdout.write(response + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
