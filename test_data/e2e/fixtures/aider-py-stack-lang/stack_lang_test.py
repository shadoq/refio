import unittest

from stack_lang import StackLangError, StackMachine


def run(source):
    return StackMachine().run(source)


def error_of(source, machine=None):
    machine = machine or StackMachine()
    with unittest.TestCase().assertRaises(StackLangError) as ctx:
        machine.run(source)
    return str(ctx.exception)


class BuiltinsTest(unittest.TestCase):
    def test_numbers_push_and_arithmetic_pops_b_then_a(self):
        self.assertEqual(run("1 2 3"), [1, 2, 3])
        self.assertEqual(run("7 2 -"), [5])
        self.assertEqual(run("6 7 *"), [42])
        self.assertEqual(run("1 2 +"), [3])

    def test_negative_numbers_are_literals(self):
        self.assertEqual(run("-4 -5 *"), [20])

    def test_division_truncates_toward_zero(self):
        self.assertEqual(run("7 2 /"), [3])
        self.assertEqual(run("-7 2 /"), [-3])
        self.assertEqual(run("7 -2 /"), [-3])
        self.assertEqual(run("-7 -2 /"), [3])

    def test_stack_words(self):
        self.assertEqual(run("1 dup"), [1, 1])
        self.assertEqual(run("1 2 drop"), [1])
        self.assertEqual(run("1 2 swap"), [2, 1])
        self.assertEqual(run("1 2 over"), [1, 2, 1])

    def test_words_are_case_insensitive(self):
        self.assertEqual(run("1 DUP Swap"), [1, 1])

    def test_empty_source_returns_the_current_stack(self):
        m = StackMachine()
        self.assertEqual(m.run(""), [])
        m.run("1 2")
        self.assertEqual(m.run("   "), [1, 2])

    def test_stack_and_words_persist_between_runs(self):
        m = StackMachine()
        m.run("1 : inc 1 + ;")
        self.assertEqual(m.run("inc inc"), [3])
        self.assertEqual(m.stack, [3])

    def test_returned_stack_is_a_copy(self):
        m = StackMachine()
        out = m.run("1 2")
        out.append(99)
        m.stack.append(98)
        self.assertEqual(m.stack, [1, 2])


class DefinitionsTest(unittest.TestCase):
    def test_simple_definition_does_nothing_until_used(self):
        m = StackMachine()
        self.assertEqual(m.run(": square dup * ;"), [])
        self.assertEqual(m.run("5 square"), [25])

    def test_definition_can_use_earlier_definitions(self):
        self.assertEqual(run(": sq dup * ; : quad sq sq ; 3 quad"), [81])

    def test_definitions_may_span_several_runs_of_text(self):
        m = StackMachine()
        m.run(": a 1 ;")
        m.run(": b a a + ;")
        self.assertEqual(m.run("b"), [2])

    def test_redefinition_does_not_change_words_defined_earlier(self):
        self.assertEqual(run(": a 1 ; : b a a + ; : a 10 ; b a"), [2, 10])

    def test_redefinition_chain_keeps_each_layer(self):
        src = ": a 1 ; : b a ; : a 2 ; : c a ; : a 3 ; a b c"
        self.assertEqual(run(src), [3, 1, 2])

    def test_a_word_can_be_defined_in_terms_of_its_previous_meaning(self):
        self.assertEqual(run(": a 1 ; : a a a + ; a"), [2])
        self.assertEqual(run(": a 1 ; : a a a + ; : a a a + ; a"), [4])

    def test_builtins_can_be_overridden(self):
        self.assertEqual(run(": + - ; 5 3 +"), [2])

    def test_overriding_a_builtin_can_use_the_builtin_it_replaces(self):
        self.assertEqual(run(": dup dup dup ; 3 dup"), [3, 3, 3])

    def test_earlier_definitions_keep_the_builtin_after_it_is_overridden(self):
        self.assertEqual(run(": double dup + ; : + * ; 3 double 4 4 +"), [6, 16])

    def test_overriding_swap_with_itself_twice_is_stable(self):
        self.assertEqual(run(": swap swap swap ; 1 2 swap"), [1, 2])

    def test_definition_names_are_case_insensitive(self):
        self.assertEqual(run(": Foo 1 ; foo FOO"), [1, 1])

    def test_definition_with_numbers_in_body_captures_them(self):
        self.assertEqual(run(": ten 10 ; : twenty ten ten + ; twenty"), [20])

    def test_empty_body_is_allowed(self):
        self.assertEqual(run(": nop ; 1 nop 2"), [1, 2])

    def test_definition_whose_body_underflows_only_fails_when_called(self):
        m = StackMachine()
        m.run(": bad + ;")
        self.assertEqual(error_of("bad", m), "stack underflow")


class ErrorsTest(unittest.TestCase):
    def test_underflow_messages(self):
        self.assertEqual(error_of("+"), "stack underflow")
        self.assertEqual(error_of("1 +"), "stack underflow")
        self.assertEqual(error_of("dup"), "stack underflow")
        self.assertEqual(error_of("1 swap"), "stack underflow")
        self.assertEqual(error_of("1 over"), "stack underflow")
        self.assertEqual(error_of("drop"), "stack underflow")

    def test_division_by_zero(self):
        self.assertEqual(error_of("4 0 /"), "divide by zero")

    def test_unknown_word_is_reported_in_lower_case(self):
        self.assertEqual(error_of("1 FOO"), "unknown word: foo")

    def test_unknown_word_inside_a_definition_fails_at_definition_time(self):
        m = StackMachine()
        self.assertEqual(error_of(": f nope ;", m), "unknown word: nope")
        self.assertEqual(error_of("f", m), "unknown word: f")

    def test_defining_a_number_is_rejected(self):
        self.assertEqual(error_of(": 5 1 ;"), "cannot define number: 5")
        self.assertEqual(error_of(": -3 1 ;"), "cannot define number: -3")

    def test_malformed_definitions(self):
        self.assertEqual(error_of(":"), "missing word name")
        self.assertEqual(error_of(": ;"), "missing word name")
        self.assertEqual(error_of(": a 1"), "unterminated definition")
        self.assertEqual(error_of(": a : b 1 ; ;"), "nested definition")
        self.assertEqual(error_of("1 ;"), "unexpected ;")

    def test_a_failed_run_leaves_stack_and_words_untouched(self):
        m = StackMachine()
        m.run("1 2 : two 2 ;")
        self.assertEqual(error_of("3 4 : late 9 ; : two 20 ; 0 /", m), "divide by zero")
        self.assertEqual(m.stack, [1, 2])
        self.assertEqual(m.run("two"), [1, 2, 2])
        self.assertEqual(error_of("late", m), "unknown word: late")

    def test_a_failed_run_does_not_leave_a_half_made_definition(self):
        m = StackMachine()
        self.assertEqual(error_of(": half 1 2", m), "unterminated definition")
        self.assertEqual(error_of("half", m), "unknown word: half")

    def test_the_machine_still_works_after_an_error(self):
        m = StackMachine()
        error_of("1 +", m)
        self.assertEqual(m.run("2 3 +"), [5])


if __name__ == "__main__":
    unittest.main()
