# Tic-tac-toe best-move exercise (AgentBench games style)

- `engine.py` - board helpers (`winner`, `render`, `WIN_LINES`); the board format is a
  9-char string, `.` = empty, `X`/`O` pieces.
- `ttt.py` - the `best_move(board)` stub; the docstring is the exact contract.
- `test_ttt.py` - the acceptance tests (do not modify them).

Implement `best_move` so `python3 -m unittest discover -p "test_*.py"` exits 0. Every
test position has exactly one optimal move under the documented depth rule (fastest
win, slowest loss).
