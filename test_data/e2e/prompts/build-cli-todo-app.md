Build a small command-line TODO application in Python, using ONLY the standard library
(no third-party packages).

Structure it across at least two modules in the project root:
- `app.py` - the CLI entry point, runnable as `python3 app.py <command> ...`.
- `store.py` - a storage module that loads and saves the task list to a file in the project root.

Required commands:
- `python3 app.py add "<task text>"` - append a new task to persistent storage.
- `python3 app.py list` - print every stored task, one per line (the exact task text must appear
  in the output).

Persistence must survive across separate process runs: store the tasks in a file (for example
`todos.json` or `todos.txt`) in the current directory, so that an `add` followed by a later,
separate `list` shows the task.

Also write your own unit tests in `tests/test_todo.py` using the `unittest` module, covering
adding and listing. They must pass with `python3 -m unittest discover -s tests`.

Keep the code minimal and correct. Do not use any third-party libraries.
