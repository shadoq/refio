import unittest

from ledger_format import render_ledger


def line(date, description, amount):
    return f"{date:<10} | {description:<24} | {amount:>14}"


HEADER = line("Date", "Description", "Amount")


def ledger(*lines):
    return "\n".join((HEADER,) + lines)


def body(entries, locale="en"):
    return render_ledger(entries, locale).split("\n")[1:]


class LedgerFormatTest(unittest.TestCase):
    def test_no_entries_gives_the_header_alone(self):
        self.assertEqual(render_ledger([]), HEADER)

    def test_header_is_exactly_the_documented_text(self):
        self.assertEqual(HEADER, "Date       | Description              |         Amount")

    def test_positive_dollars_end_with_a_space_in_the_closing_parenthesis_column(self):
        self.assertEqual(
            render_ledger([("2024-03-01", "Coffee beans", "USD", 1250)]),
            ledger("2024-03-01 | Coffee beans             |        $12.50 "),
        )

    def test_negative_dollars_are_in_parentheses_without_a_sign(self):
        self.assertEqual(
            render_ledger([("2024-03-01", "Refund", "USD", -1250)]),
            ledger("2024-03-01 | Refund                   |       ($12.50)"),
        )

    def test_en_places_the_euro_symbol_before_the_number(self):
        out = body([("2024-03-01", "a", "EUR", 1250), ("2024-03-02", "b", "EUR", -1250)])
        self.assertEqual(out, [
            line("2024-03-01", "a", "€12.50 "),
            line("2024-03-02", "b", "(€12.50)"),
        ])

    def test_de_places_the_symbol_after_the_number_with_comma_decimals(self):
        out = body([("2024-03-01", "a", "EUR", 1250), ("2024-03-02", "b", "EUR", -1250)], "de")
        self.assertEqual(out, [
            line("2024-03-01", "a", "12,50 € "),
            line("2024-03-02", "b", "(12,50 €)"),
        ])

    def test_de_applies_the_same_rule_to_dollars(self):
        self.assertEqual(body([("2024-03-01", "a", "USD", 99)], "de"),
                         [line("2024-03-01", "a", "0,99 $ ")])

    def test_thousands_separators_follow_the_locale(self):
        entries = [("2024-01-01", "big", "USD", 123456789), ("2024-01-02", "round", "USD", 100000)]
        self.assertEqual(body(entries), [line("2024-01-01", "big", "$1,234,567.89 "),
                                         line("2024-01-02", "round", "$1,000.00 ")])
        self.assertEqual(body([("2024-01-01", "big", "EUR", -123456789)], "de"),
                         [line("2024-01-01", "big", "(1.234.567,89 €)")])

    def test_small_amounts_keep_two_decimals_and_a_leading_zero(self):
        out = body([
            ("2024-01-01", "z", "USD", 0),
            ("2024-01-02", "p", "USD", 5),
            ("2024-01-03", "n", "USD", -5),
        ])
        self.assertEqual(out, [
            line("2024-01-01", "z", "$0.00 "),
            line("2024-01-02", "p", "$0.05 "),
            line("2024-01-03", "n", "($0.05)"),
        ])

    def test_wide_amount_fills_the_column_exactly(self):
        row = body([("2024-01-01", "x", "USD", 123456789)])[0]
        self.assertEqual(len(row), 10 + 3 + 24 + 3 + 14)

    def test_description_of_exactly_24_characters_is_kept(self):
        d = "abcdefghijklmnopqrstuvwx"
        self.assertEqual(len(d), 24)
        self.assertEqual(body([("2024-01-01", d, "USD", 100)]), [line("2024-01-01", d, "$1.00 ")])

    def test_description_of_25_characters_is_cut_to_21_plus_dots(self):
        d = "abcdefghijklmnopqrstuvwxy"
        self.assertEqual(body([("2024-01-01", d, "USD", 100)]),
                         [line("2024-01-01", "abcdefghijklmnopqrstu...", "$1.00 ")])

    def test_truncation_counts_characters_not_bytes(self):
        d = "Café au lait with extra cream"
        self.assertEqual(body([("2024-01-01", d, "EUR", 100)]),
                         [line("2024-01-01", "Café au lait with ext...", "€1.00 ")])

    def test_sorted_by_date_then_description_then_amount(self):
        entries = [
            ("2024-02-01", "b", "USD", 100),
            ("2024-01-31", "z", "USD", 100),
            ("2024-02-01", "a", "USD", 900),
            ("2024-02-01", "a", "USD", -300),
            ("2024-02-01", "a", "USD", 0),
        ]
        self.assertEqual(body(entries), [
            line("2024-01-31", "z", "$1.00 "),
            line("2024-02-01", "a", "($3.00)"),
            line("2024-02-01", "a", "$0.00 "),
            line("2024-02-01", "a", "$9.00 "),
            line("2024-02-01", "b", "$1.00 "),
        ])

    def test_descriptions_compare_as_plain_strings(self):
        entries = [("2024-01-01", "alpha", "USD", 1), ("2024-01-01", "Zed", "USD", 1)]
        self.assertEqual([r[13:18] for r in body(entries)], ["Zed  ", "alpha"])

    def test_sorting_uses_the_full_description_not_the_cut_one(self):
        base = "x" * 30
        entries = [("2024-01-01", base + "b", "USD", 1), ("2024-01-01", base + "a", "USD", 2)]
        out = body(entries)
        self.assertTrue(out[0].endswith("$0.02 "))
        self.assertTrue(out[1].endswith("$0.01 "))

    def test_equal_entries_keep_input_order(self):
        entries = [("2024-01-01", "same", "EUR", 100), ("2024-01-01", "same", "USD", 100)]
        out = body(entries)
        self.assertIn("€", out[0])
        self.assertIn("$", out[1])

    def test_input_is_not_modified(self):
        entries = [("2024-02-01", "b", "USD", 1), ("2024-01-01", "a", "USD", 1)]
        copy = list(entries)
        render_ledger(entries)
        self.assertEqual(entries, copy)

    def test_unknown_locale_and_currency_raise_value_error(self):
        with self.assertRaises(ValueError):
            render_ledger([], locale="fr")
        with self.assertRaises(ValueError):
            render_ledger([("2024-01-01", "a", "GBP", 1)])


if __name__ == "__main__":
    unittest.main()
