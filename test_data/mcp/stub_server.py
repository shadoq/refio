#!/usr/bin/env python3
"""
Minimal MCP (Model Context Protocol) stub server for Refio manual tests 52-58.

Transport:  stdio, newline-delimited JSON-RPC 2.0 (one JSON object per line),
            matching Refio's MCPStdioTransport (writes msg + "\\n", reads by line).
Stdlib only — no pip install, no node. Works fully offline.

Capabilities advertised: resources + tools (NOT prompts).

Methods implemented:
  initialize                  -> protocolVersion + capabilities + serverInfo
  notifications/initialized   -> (notification, no reply)
  tools/list                  -> one tool: echo_marker(text)
  tools/call  echo_marker     -> content text "MCP_ECHO{<text>}"
  resources/list              -> one resource: project-notes.md
  resources/read              -> contents[].text of project-notes.md

Anything else with an id gets a JSON-RPC "method not found" error so the client
never hangs. Diagnostics go to stderr (Refio logs stderr at debug level).

Run standalone for a smoke check:
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize"}' | python stub_server.py
"""
import json
import os
import sys

PROTOCOL_VERSION = "2025-06-18"
RESOURCE_URI = "fixture://project-notes.md"
NOTES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resources", "project-notes.md")

ECHO_TOOL = {
    "name": "echo_marker",
    "description": "Echo the given text back wrapped as MCP_ECHO{...}. Used to prove an MCP tool call round-tripped.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Text to echo back."}
        },
        "required": ["text"],
    },
}


def log(msg):
    sys.stderr.write("[stub_server] " + msg + "\n")
    sys.stderr.flush()


def read_notes():
    try:
        with open(NOTES_PATH, "r", encoding="utf-8") as fh:
            return fh.read()
    except OSError as exc:
        log("could not read notes: %s" % exc)
        return "MCP resource unavailable: %s" % exc


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def reply(req_id, result):
    send({"jsonrpc": "2.0", "id": req_id, "result": result})


def reply_error(req_id, code, message):
    send({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})


def handle(msg):
    method = msg.get("method")
    req_id = msg.get("id")
    is_request = req_id is not None

    if method == "initialize":
        reply(req_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"resources": {}, "tools": {}},
            "serverInfo": {"name": "refio-stub", "version": "1.0.0"},
        })
    elif method == "notifications/initialized":
        pass  # notification, no reply
    elif method == "tools/list":
        reply(req_id, {"tools": [ECHO_TOOL]})
    elif method == "tools/call":
        params = msg.get("params") or {}
        name = params.get("name")
        args = params.get("arguments") or {}
        if name != "echo_marker":
            reply(req_id, {"isError": True, "content": [{"type": "text", "text": "Unknown tool: %s" % name}]})
        else:
            text = str(args.get("text", ""))
            reply(req_id, {"isError": False, "content": [{"type": "text", "text": "MCP_ECHO{%s}" % text}]})
    elif method == "resources/list":
        reply(req_id, {"resources": [{
            "uri": RESOURCE_URI,
            "name": "project-notes.md",
            "description": "Fixture project notes (contains an MCP retrieval needle).",
            "mimeType": "text/markdown",
        }]})
    elif method == "resources/read":
        params = msg.get("params") or {}
        uri = params.get("uri", RESOURCE_URI)
        reply(req_id, {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": read_notes()}]})
    elif is_request:
        reply_error(req_id, -32601, "Method not found: %s" % method)
    # else: unknown notification -> ignore


def main():
    log("started; notes=%s" % NOTES_PATH)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError as exc:
            log("bad json: %s" % exc)
            continue
        try:
            handle(msg)
        except Exception as exc:  # never die on a single bad message
            log("handler error: %s" % exc)
            if msg.get("id") is not None:
                reply_error(msg.get("id"), -32603, "Internal error: %s" % exc)


if __name__ == "__main__":
    main()
