"""WRONG heuristic bot: takes the first free corner, else the first free cell."""

from engine import winner


def best_move(board):
    corners = [0, 2, 6, 8]
    for i in corners:
        if board[i] == ".":
            return i
    for i in range(9):
        if board[i] == ".":
            return i
    return None
