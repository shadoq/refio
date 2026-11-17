"""Unbeatable move selection - implement the documented function.

The docstring is the contract (including the depth rule); the unit tests in
test_ttt.py are the acceptance bar (they are not to be modified).
"""

from engine import winner


def _minimax(board, player, depth):
    w = winner(board)
    if w == "X":
        return 10 - depth
    if w == "O":
        return depth - 10
    if "." not in board:
        return 0
    values = []
    for i in range(9):
        if board[i] == ".":
            nxt = board[:i] + player + board[i + 1:]
            values.append(_minimax(nxt, "O" if player == "X" else "X", depth + 1))
    return max(values) if player == "X" else min(values)


def best_move(board):
    """Return the index (0-8) X should play on `board` (X to move).

    Optimality rule: X must guarantee the best achievable outcome (win over draw
    over loss) against best play, and among equally-valued moves prefer the
    **fastest win**; when losing anyway, prefer the **slowest loss**. Every test
    position has exactly one such move.

    >>> best_move("XX.O.....")
    2
    >>> best_move("O........")
    4
    """
    best_value = None
    best_index = None
    for i in range(9):
        if board[i] == ".":
            nxt = board[:i] + "X" + board[i + 1:]
            value = _minimax(nxt, "O", 1)
            if best_value is None or value > best_value:
                best_value, best_index = value, i
    return best_index
