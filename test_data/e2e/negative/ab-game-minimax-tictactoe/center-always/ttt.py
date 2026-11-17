"""WRONG heuristic bot: always takes the center when free, else the first empty cell."""

from engine import winner


def best_move(board):
    if board[4] == ".":
        return 4
    for i in range(9):
        if board[i] == ".":
            return i
    return None
