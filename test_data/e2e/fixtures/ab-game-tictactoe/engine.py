"""Tic-tac-toe helpers for the best-move exercise (stdlib only).

The board is a 9-character string, row-major; `.` marks an empty cell, pieces are
`X` and `O`. `best_move` (implemented in ttt.py) is always called for X to move.
"""

WIN_LINES = (
    (0, 1, 2), (3, 4, 5), (6, 7, 8),
    (0, 3, 6), (1, 4, 7), (2, 5, 8),
    (0, 4, 8), (2, 4, 6),
)


def winner(board):
    """Return 'X' or 'O' if that piece has three in a row, else None."""
    for a, b, c in WIN_LINES:
        if board[a] != "." and board[a] == board[b] == board[c]:
            return board[a]
    return None


def render(board):
    """Pretty-print a board for debugging."""
    rows = [board[r:r + 3] for r in (0, 3, 6)]
    return "\n".join(" ".join(cell if cell != "." else "_" for cell in row) for row in rows)
