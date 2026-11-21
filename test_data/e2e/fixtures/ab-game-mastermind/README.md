# Mastermind solver exercise (AgentBench games style)

- `engine.py` - scoring helpers (`score`, `is_valid_guess`); colors RGBYOP, 4 pegs,
  repeats allowed.
- `run_game.py` - the game driver and acceptance bar (do not modify it): it plays your
  solver against the real secret, requires every guess to be consistent with all
  previous feedback, and allows at most 7 attempts.
- `solver.py` - the `next_guess(history)` stub; the docstring is the exact contract.

Implement the solver so `python3 run_game.py` prints `SOLVED in N guesses` and exits 0.
