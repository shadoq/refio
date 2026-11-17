#!/usr/bin/env python3
"""Game driver for the mastermind exercise: plays solver.next_guess against the
fixed secret and enforces the consistency contract.

Rules enforced here (the solver cannot break them from the outside):
- every guess must be a valid 4-color string,
- every guess must be CONSISTENT with all previous feedback: scoring the guess
  against each previous guess must reproduce that attempt's (exact, partial),
- at most 7 attempts.

Exit 0 + "SOLVED in N guesses" when the secret is found in time.
"""
import sys

from engine import COLORS, is_valid_guess, score

SECRET = "BGYR"
MAX_ATTEMPTS = 7


def main() -> int:
    sys.path.insert(0, ".")
    import solver

    history = []
    for attempt in range(1, MAX_ATTEMPTS + 1):
        guess = solver.next_guess(list(history))
        if not isinstance(guess, str) or not is_valid_guess(guess):
            print(f"NOT SOLVED: attempt {attempt} produced invalid guess {guess!r}")
            return 1
        exact, partial = score(SECRET, guess)
        for prev, prev_exact, prev_partial in history:
            if score(guess, prev) != (prev_exact, prev_partial):
                print(f"NOT SOLVED: attempt {attempt} ({guess}) contradicts the "
                      f"feedback of {prev!r}")
                return 1
        print(f"attempt {attempt}: {guess} -> exact={exact} partial={partial}")
        if exact == 4:
            print(f"SOLVED in {attempt} guesses")
            return 0
        history.append((guess, exact, partial))
    print(f"NOT SOLVED: no consistent solution within {MAX_ATTEMPTS} attempts")
    return 1


if __name__ == "__main__":
    sys.exit(main())
