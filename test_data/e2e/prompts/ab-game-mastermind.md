Implement a Mastermind solver in `solver.py`: `next_guess(history)` returns the next
4-color guess (colors RGBYOP, repeats allowed). `history` holds the previous attempts as
`(guess, exact, partial)` tuples, and every returned guess must be **consistent** with
all of them - scoring the guess against each previous guess must reproduce that
attempt's feedback exactly (this is the classic consistency filter; it also forbids
blind guessing). The game must be solved within 7 attempts against the real secret.

`python3 run_game.py` is the acceptance bar: it plays your solver against the actual
game and prints `SOLVED in N guesses` on success. Do not modify `run_game.py`,
`engine.py` or the README.
