`pipeline.py` is the metrics relay's ingest stage in the procedural style it
was born in: the fold state lives in module-level globals (`STATE`,
`COUNTER`, `LOG`), three functions mutate them directly, and `main()`
orchestrates from the sidelines. It works, but the state is nowhere
explicit, the pipeline cannot be instantiated twice, and every test has to
reset globals to stay isolated.

Your task:

1. Refactor `pipeline.py` around a `Pipeline` class: the fold state created
   explicitly in `__init__`, the fold logic as methods, and a read-only view
   of the tally exposed through a `@property` (fresh copies, never the live
   dicts).
2. Add type hints throughout, and leave no module-level mutable state behind.
3. Keep the public surface working unchanged: the module-level
   `process_record`, `replay`, `summarize` and `reset` stay as thin wrappers
   delegating to a shared default instance, and the CLI
   (`python3 pipeline.py '<json-record>'...`) prints the same summary JSON
   with the same exit codes.
4. Preserve the behaviour exactly: the same validation reasons, the same
   sequence numbers, the same per-source attribution and the same summary
   shape. Run the acceptance gate with `python3 checks.py` - it pins the
   public API and the CLI, so it must stay green before and after the
   refactor.

Do not modify `checks.py` or the README: the refactor belongs in
`pipeline.py`.
