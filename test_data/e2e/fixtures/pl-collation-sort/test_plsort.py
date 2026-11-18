import ast
import os
import unittest

from plsort import sort_surnames


class PolishOrderTests(unittest.TestCase):
    def test_diacritic_letters_follow_their_base_letter(self):
        self.assertEqual(sort_surnames(["Gbur", "Gąsior", "Gasior", "Galas"]),
                         ["Galas", "Gasior", "Gąsior", "Gbur"])

    def test_l_before_slashed_l(self):
        self.assertEqual(sort_surnames(["Łoś", "Lubicz", "Łabędź", "Lis", "Mazur"]),
                         ["Lis", "Lubicz", "Łabędź", "Łoś", "Mazur"])

    def test_o_oacute_p(self):
        self.assertEqual(sort_surnames(["Pawlak", "Ósmy", "Olek", "Ozga"]),
                         ["Olek", "Ozga", "Ósmy", "Pawlak"])

    def test_s_before_s_acute_and_digraph_letters_are_plain_sequences(self):
        self.assertEqual(sort_surnames(["Śliwa", "Szymański", "Sosna", "Sikora"]),
                         ["Sikora", "Sosna", "Szymański", "Śliwa"])
        self.assertEqual(sort_surnames(["Ćwik", "Czarny", "Cichy"]), ["Cichy", "Czarny", "Ćwik"])

    def test_z_then_acute_then_dot(self):
        self.assertEqual(sort_surnames(["Żak", "Źrebiec", "Zdun", "Zając"]),
                         ["Zając", "Zdun", "Źrebiec", "Żak"])

    def test_second_letter_decides_after_equal_first_letters(self):
        self.assertEqual(sort_surnames(["Ęgowski", "Eryk", "Ebert", "Ęż"]),
                         ["Ebert", "Eryk", "Ęgowski", "Ęż"])
        self.assertEqual(sort_surnames(["Kwiatkowska", "Kwiat", "Kwiatek"]),
                         ["Kwiat", "Kwiatek", "Kwiatkowska"])

    def test_case_is_ignored_and_equal_names_keep_input_order(self):
        names = ["nowak", "Adamski", "Nowak", "NOWAK", "ŁAZARZ", "łazarz"]
        self.assertEqual(sort_surnames(names), ["Adamski", "ŁAZARZ", "łazarz", "nowak", "Nowak", "NOWAK"])
        self.assertEqual(sort_surnames(["Nowak", "nowak"]), ["Nowak", "nowak"])
        self.assertEqual(sort_surnames(["nowak", "Nowak"]), ["nowak", "Nowak"])

    def test_hyphen_and_space_sort_before_letters(self):
        self.assertEqual(sort_surnames(["Nowakowski", "Nowak-Zielińska", "Nowak", "Nowak Jeziorny"]),
                         ["Nowak", "Nowak Jeziorny", "Nowak-Zielińska", "Nowakowski"])

    def test_input_is_not_modified_and_result_is_a_new_list(self):
        names = ["Żak", "Adam"]
        result = sort_surnames(names)
        self.assertEqual(names, ["Żak", "Adam"])
        self.assertIsNot(result, names)
        self.assertEqual(sort_surnames([]), [])

    def test_full_alphabet_order(self):
        alphabet = "aąbcćdeęfghijklłmnńoóprsśtuwyzźż"
        shuffled = list(reversed(alphabet))
        self.assertEqual("".join(sort_surnames(shuffled)), alphabet)

    def test_does_not_depend_on_the_os_locale(self):
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "plsort.py"), encoding="utf-8") as fh:
            tree = ast.parse(fh.read())
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                self.assertNotIn("locale", [a.name for a in node.names])
            if isinstance(node, ast.ImportFrom):
                self.assertNotEqual(node.module, "locale")


if __name__ == "__main__":
    unittest.main()
