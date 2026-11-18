import unittest

from league_table import tally

HEADER = "Team                           | MP |  W |  D |  L |  GD |  P"


def row(name, mp, w, d, l, gd, p):
    return f"{name:<30} | {mp:>2} | {w:>2} | {d:>2} | {l:>2} | {gd:>3} | {p:>2}"


def table(*rows):
    return "\n".join((HEADER,) + rows)


class LeagueTableTest(unittest.TestCase):
    def test_no_matches_gives_the_header_alone(self):
        self.assertEqual(tally([]), HEADER)

    def test_exact_layout_of_a_win(self):
        self.assertEqual(
            tally(["Blue Bears;Red Rams;2-0"]),
            "Team                           | MP |  W |  D |  L |  GD |  P\n"
            "Blue Bears                     |  1 |  1 |  0 |  0 |  +2 |  3\n"
            "Red Rams                       |  1 |  0 |  0 |  1 |  -2 |  0",
        )

    def test_a_draw_orders_level_teams_by_name(self):
        self.assertEqual(
            tally(["Zebras;Ants;1-1"]),
            table(row("Ants", 1, 0, 1, 0, "+0", 1), row("Zebras", 1, 0, 1, 0, "+0", 1)),
        )

    def test_whitespace_and_blank_lines_are_ignored(self):
        self.assertEqual(
            tally(["  Red Rams ; Blue Bears ; 0-0 ", "", "Blue Bears;Red Rams;3-1"]),
            table(row("Blue Bears", 2, 1, 1, 0, "+2", 4), row("Red Rams", 2, 0, 1, 1, "-2", 1)),
        )

    def test_head_to_head_beats_goal_difference(self):
        # X and Y are level on 3 points; Y has the far better goal difference, but X won the
        # match between them.
        self.assertEqual(
            tally(["X;Y;1-0", "Y;W;6-0", "X;V;0-1", "W;V;0-0"]),
            table(
                row("V", 2, 1, 1, 0, "+1", 4),
                row("X", 2, 1, 0, 1, "+0", 3),
                row("Y", 2, 1, 0, 1, "+5", 3),
                row("W", 2, 0, 1, 1, "-6", 1),
            ),
        )

    def test_a_circular_three_way_tie_falls_back_to_goal_difference(self):
        self.assertEqual(
            tally(["X;Y;1-0", "Y;Z;5-0", "Z;X;1-0"]),
            table(
                row("Y", 2, 1, 0, 1, "+4", 3),
                row("X", 2, 1, 0, 1, "+0", 3),
                row("Z", 2, 1, 0, 1, "-4", 3),
            ),
        )

    def test_goals_scored_break_an_equal_goal_difference(self):
        self.assertEqual(
            tally(["P;Q;2-2", "P;S;4-1", "T;Q;0-3"]),
            table(
                row("P", 2, 1, 1, 0, "+3", 4),
                row("Q", 2, 1, 1, 0, "+3", 4),
                row("S", 1, 0, 0, 1, "-3", 0),
                row("T", 1, 0, 0, 1, "-3", 0),
            ),
        )

    def test_invalid_lines_report_their_position_counting_blank_lines(self):
        cases = {
            "line 3:": ["A;B;1-0", "", "A;B;x-1"],
            "line 1:": ["A;A;1-0"],
            "line 2:": ["A;B;1-0", "A;B"],
        }
        for prefix, rows in cases.items():
            with self.subTest(rows=rows):
                with self.assertRaises(ValueError) as raised:
                    tally(rows)
                self.assertTrue(str(raised.exception).startswith(prefix), str(raised.exception))

    def test_malformed_scores_and_names_are_rejected(self):
        for line in ["A;B;1-0-2", "A;;1-0", "A;B;-1-0", "A;B;1:0", "A;B;"]:
            with self.subTest(line=line):
                with self.assertRaises(ValueError):
                    tally([line])


if __name__ == "__main__":
    unittest.main()
