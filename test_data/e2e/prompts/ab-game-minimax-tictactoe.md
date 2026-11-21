Implement an unbeatable move selector for tic-tac-toe: `best_move(board)` in `ttt.py`
returns the index (0-8) that X should play on the 9-character board (`.` = empty; the
format and helpers are in `engine.py`). Optimality rule (documented in the stub): best
outcome against best play - win over draw over loss - and among equally-valued moves the
**fastest win**; when losing anyway, the **slowest loss**. Every position in
`test_ttt.py` has exactly one such move (win-now, block, fork creation, fork blocking,
double-threat setup, opening center). `python3 -m unittest discover -p "test_*.py"` must
exit 0. Do not modify the tests or the engine.
