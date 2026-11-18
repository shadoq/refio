We are preparing the `shelf` tool for translation. Right now every user-facing text is a string literal inside `shelf/cli.py`: the command line help and description, the success lines, the error lines and the statistics lines.

Move all of that wording into a new catalog module `shelf/messages.py` and make `shelf/cli.py` use it. Pick whatever catalog design you like, but keep in mind that the singular and plural forms of the list summary will need separate translations later. The visible output of the tool must stay exactly the same.

`python checks.py` runs the existing tests and verifies that the wording lives in the catalog and no message text is left in `cli.py`. Do not edit `tests/` or `checks.py`.
