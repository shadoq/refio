"""Acceptance tests for the best-move exercise - do not modify."""
import unittest

from ttt import best_move


class TestBestMove(unittest.TestCase):
    def test_win_now(self):
        # X on the top row can complete the row immediately.
        self.assertEqual(best_move("XX.O....."), 2)

    def test_block(self):
        # O threatens OO. - X must block or lose.
        self.assertEqual(best_move("OO.X....."), 2)

    def test_fork_create(self):
        # Only one move creates an unanswerable double threat.
        self.assertEqual(best_move("X...X.O.."), 8)

    def test_block_fork(self):
        self.assertEqual(best_move("XO..X.O.."), 8)

    def test_double_threat_setup(self):
        self.assertEqual(best_move("X.O.X...."), 8)

    def test_opening_center(self):
        # O took a corner; X must take the center.
        self.assertEqual(best_move("O........"), 4)


if __name__ == "__main__":
    unittest.main()
