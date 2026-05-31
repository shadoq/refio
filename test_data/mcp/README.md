# MCP stub server — test fixture

`stub_server.py` is a minimal, stdlib-only MCP server over **stdio**
(newline-delimited JSON-RPC 2.0), matching Refio's `MCPStdioTransport`. It runs
fully offline — no `pip install`, no node/npx. Used by manual tests **T52–T58**.

## What it exposes

- **Tool** `echo_marker(text)` → returns content text `MCP_ECHO{<text>}`.
- **Resource** `fixture://project-notes.md` → the contents of
  [`resources/project-notes.md`](resources/project-notes.md), which contains the
  needle `MCP_RES_NEEDLE{notes_4F}` and a prompt-injection probe (T58).
- Capabilities advertised: `resources` + `tools` (no `prompts`).

## Standalone smoke check

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | python stub_server.py
```

Expect a single-line JSON reply with `protocolVersion` and
`capabilities: {resources, tools}`.

## Config to paste into the IntelliJ MCP settings (Settings → MCP → Add Custom Server)

| Field | Value |
|-------|-------|
| Type | `STDIO` |
| Command | `python` (or `python3` / absolute path to your interpreter) |
| Args (comma) | `D:\_work\Saas\refio\test_data\mcp\stub_server.py` |
| Working Dir | `D:\_work\Saas\refio\test_data\mcp` |
| Access Mode | `READ` (flip to `READ_WRITE` for T55) |
| Enable tools | ✓ |
| Enable resources | ✓ |
| Tools exposure | `TOOLS` (switch to `CONTEXT` for T56) |

For the **CONTEXT** exposure test (T56) also set:

| Field | Value |
|-------|-------|
| Context tool name | `echo_marker` |
| Context query param | `text` |
| Tool Param Mapping | `echo_marker` → `text` |

> On Windows, if `python` is not on PATH use the full interpreter path in the
> Command field. Verify with `python --version` first.
