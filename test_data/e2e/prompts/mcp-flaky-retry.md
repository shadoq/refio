An MCP server named `stubflaky` is connected. It exposes a probe tool.

Call that tool and write its answer into a new file named `probe.txt` in the project root.

The tool is known to be unreliable and may fail on the first attempt. If it returns an error,
call it again rather than giving up or inventing an answer.
