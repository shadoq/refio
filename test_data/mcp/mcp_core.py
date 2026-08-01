#!/usr/bin/env python3
"""
Shared MCP server logic for the Refio stub fixtures.

Stdlib only, fully offline. The transports (stdio / HTTP / SSE) are thin frontends over this
module, so a scenario proves the transport rather than a different server per transport.

Profiles emulate the shapes of real MCP providers that Refio is expected to talk to:

  legacy    echo_marker(text) + the project-notes resource. The historical stub, kept intact so
            manual tests T52-T58 keep working.
  docs      A documentation lookup like context7: query-docs(library, topic). Also the profile
            used for the CONTEXT exposure test, since that is the mode context7 ships with.
  terminal  A mutating tool, run_command(command), for checking that a READ server does not
            expose it.
  notes     Resources only, for the resources/read path.
  flaky     Fails the first call of a tool and succeeds on the next, for retry behaviour.

Every tool answer carries a distinctive needle (MCP_DOCS{...} and friends) so an e2e assertion can
demand that the value reached a file on disk, not merely that a tool was reported as called.
"""
import hashlib
import json
import os

import context7_fixture

# Fixed so a scenario can precompute the expected needle; never sent to the model.
_RECEIPT_SALT = "refio-mcp-fixture-v2"

PROTOCOL_VERSION = "2025-06-18"

RESOURCE_URI = "fixture://project-notes.md"
NOTES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resources", "project-notes.md")

# JSON-RPC error codes we emit.
METHOD_NOT_FOUND = -32601
INTERNAL_ERROR = -32603


def _receipt(kind, payload):
    """
    A value the model cannot produce without actually calling the tool.

    An earlier version of these fixtures spelled the needle format out in the tool description,
    so a model could write `MCP_DOCS{library=ktor,...}` into a file without ever calling anything
    and the assertion still passed. The digest fixes that: it is derived from the arguments with a
    secret this server never discloses, so a matching needle in a file is proof of a round trip.
    """
    digest = hashlib.sha256((_RECEIPT_SALT + "|" + payload).encode("utf-8")).hexdigest()[:12]
    return "MCP_%s{%s}" % (kind, digest)


def receipt_for(kind, payload):
    """Same value, for a test harness that needs to know what to expect."""
    return _receipt(kind, payload)


def _text_result(text, is_error=False):
    return {"isError": is_error, "content": [{"type": "text", "text": text}]}


def _read_notes():
    try:
        with open(NOTES_PATH, "r", encoding="utf-8") as fh:
            return fh.read()
    except OSError as exc:
        return "MCP resource unavailable: %s" % exc


ECHO_TOOL = {
    "name": "echo_marker",
    "description": "Echo the given text back wrapped as MCP_ECHO{...}. Proves a tool call round-tripped.",
    "inputSchema": {
        "type": "object",
        "properties": {"text": {"type": "string", "description": "Text to echo back."}},
        "required": ["text"],
    },
}

DOCS_TOOL = {
    "name": "query-docs",
    "description": (
        "Look up documentation for a library. Returns the matching documentation snippet."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "library": {"type": "string", "description": "Library name, e.g. 'ktor'."},
            "topic": {"type": "string", "description": "Topic to look up, e.g. 'routing'."},
        },
        "required": ["library"],
    },
}

TERMINAL_TOOL = {
    "name": "run_command",
    "description": "Run a shell command on the MCP host. Mutating: only a READ_WRITE server may expose it.",
    "inputSchema": {
        "type": "object",
        "properties": {"command": {"type": "string", "description": "Command line to run."}},
        "required": ["command"],
    },
    "annotations": {"readOnlyHint": False, "destructiveHint": True},
}

RESOLVE_TOOL = {
    "name": "resolve-library-id",
    "description": (
        "Resolve a package or product name to a context7-compatible library ID. "
        "Call this before requesting documentation, and use the ID it returns verbatim."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "libraryName": {"type": "string", "description": "Library name to search for."}
        },
        "required": ["libraryName"],
    },
}

CONTEXT7_DOCS_TOOL = {
    "name": "query-docs",
    "description": (
        "Fetch documentation for a library. Requires a context7-compatible library ID obtained "
        "from resolve-library-id."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "libraryId": {"type": "string", "description": "Library ID, e.g. /owner/repo."},
            "query": {"type": "string", "description": "Topic to look up, e.g. 'routing'."},
        },
        "required": ["libraryId"],
    },
}

FLAKY_TOOL = {
    "name": "flaky_probe",
    "description": "Returns a probe token. May fail transiently; retry if it errors.",
    "inputSchema": {"type": "object", "properties": {}},
}


class StubServer:
    """
    Transport-independent MCP server.

    `handle` takes a decoded JSON-RPC message and returns the response object, or None for a
    notification, which by protocol gets no reply.
    """

    def __init__(self, profile="legacy", log=None):
        self.profile = profile
        self.log = log or (lambda _msg: None)
        self._flaky_calls = 0

    # -- capability surface -------------------------------------------------

    def tools(self):
        if self.profile == "context7":
            return [RESOLVE_TOOL, CONTEXT7_DOCS_TOOL]
        if self.profile == "docs":
            return [DOCS_TOOL]
        if self.profile == "terminal":
            return [TERMINAL_TOOL]
        if self.profile == "flaky":
            return [FLAKY_TOOL]
        if self.profile == "notes":
            return []
        return [ECHO_TOOL]

    def resources(self):
        if self.profile in ("legacy", "notes"):
            return [{
                "uri": RESOURCE_URI,
                "name": "project-notes.md",
                "description": "Fixture project notes (contains an MCP retrieval needle).",
                "mimeType": "text/markdown",
            }]
        return []

    def capabilities(self):
        caps = {}
        if self.tools():
            caps["tools"] = {}
        if self.resources():
            caps["resources"] = {}
        return caps

    # -- tool dispatch ------------------------------------------------------

    def call_tool(self, name, args):
        if name == "echo_marker":
            return _text_result("MCP_ECHO{%s}" % args.get("text", ""))

        # Both profiles expose a tool called query-docs, with different schemas. Keep the generic
        # one from swallowing the context7 call.
        if name == "query-docs" and self.profile != "context7":
            library = str(args.get("library", ""))
            topic = str(args.get("topic", "")) or "overview"
            return _text_result(
                "%s\n\nRetrieved from the fixture documentation index."
                % _receipt("DOCS", library + "/" + topic)
            )

        if name == "run_command":
            return _text_result("MCP_TERM{%s}" % args.get("command", ""))

        if name == "resolve-library-id":
            results = context7_fixture.search(args.get("libraryName", ""))
            if not results:
                return _text_result(
                    "No libraries matched '%s'. Try a different name."
                    % args.get("libraryName", ""),
                    is_error=True,
                )
            lines = ["Matching libraries (use the ID verbatim):", ""]
            for r in results:
                lines.append("- %s  ->  %s" % (r["title"], r["id"]))
            return _text_result("\n".join(lines))

        if name == "query-docs" and self.profile == "context7":
            library_id = str(args.get("libraryId", ""))
            query = str(args.get("query", "")) or "overview"
            body, found = context7_fixture.documentation(library_id, query)
            if not found:
                # Mirrors the documented 404: an unresolved id must fail, not silently succeed,
                # otherwise a model that skips resolve-library-id still looks correct.
                return _text_result(
                    "Library not found: '%s'. Resolve the ID with resolve-library-id first."
                    % library_id,
                    is_error=True,
                )
            # The receipt binds the RESOLVED id to the topic, so a guessed id yields a different
            # digest and the scenario fails - which is the point of the two-step workflow.
            # Labelled like a request id in a real API response. Naming the field keeps the task
            # unambiguous; the digest stays unguessable, which is what makes it evidence.
            return _text_result(
                "Retrieval-Token: %s\n\n%s"
                % (_receipt("C7", library_id + "|" + query), body)
            )

        if name == "flaky_probe":
            self._flaky_calls += 1
            if self._flaky_calls == 1:
                return None  # signals: answer with a JSON-RPC error
            return _text_result(_receipt("FLAKY", "recovered"))

        return _text_result("Unknown tool: %s" % name, is_error=True)

    # -- JSON-RPC -----------------------------------------------------------

    def handle(self, msg):
        method = msg.get("method")
        req_id = msg.get("id")
        is_request = req_id is not None

        if method == "initialize":
            return self._ok(req_id, {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": self.capabilities(),
                "serverInfo": {"name": "refio-stub-%s" % self.profile, "version": "2.0.0"},
            })

        if method == "notifications/initialized":
            return None

        if method == "tools/list":
            return self._ok(req_id, {"tools": self.tools()})

        if method == "tools/call":
            params = msg.get("params") or {}
            name = params.get("name")
            result = self.call_tool(name, params.get("arguments") or {})
            if result is None:
                return self._err(req_id, INTERNAL_ERROR, "Transient failure from %s" % name)
            return self._ok(req_id, result)

        if method == "resources/list":
            return self._ok(req_id, {"resources": self.resources()})

        if method == "resources/read":
            params = msg.get("params") or {}
            uri = params.get("uri", RESOURCE_URI)
            return self._ok(req_id, {
                "contents": [{"uri": uri, "mimeType": "text/markdown", "text": _read_notes()}]
            })

        if is_request:
            # Answering rather than staying silent, so a client never waits out its timeout.
            return self._err(req_id, METHOD_NOT_FOUND, "Method not found: %s" % method)
        return None

    def handle_raw(self, line):
        """Decode, handle, and encode. Returns a JSON string or None."""
        try:
            msg = json.loads(line)
        except ValueError as exc:
            self.log("bad json: %s" % exc)
            return None
        try:
            response = self.handle(msg)
        except Exception as exc:  # a single bad message must not kill the server
            self.log("handler error: %s" % exc)
            req_id = msg.get("id")
            if req_id is None:
                return None
            response = self._err(req_id, INTERNAL_ERROR, "Internal error: %s" % exc)
        return json.dumps(response) if response is not None else None

    @staticmethod
    def _ok(req_id, result):
        return {"jsonrpc": "2.0", "id": req_id, "result": result}

    @staticmethod
    def _err(req_id, code, message):
        return {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}


PROFILES = ("legacy", "docs", "terminal", "notes", "flaky", "context7")
