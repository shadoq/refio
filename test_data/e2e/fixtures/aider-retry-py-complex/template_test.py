import unittest

from lookup import resolve
from template import TemplateError, render

CTX = {
    "name": "Ada",
    "count": 0,
    "flag": False,
    "nothing": None,
    "empty": "",
    "user": {"address": {"city": "Oslo"}, "tags": ["a", "b"]},
    "items": [{"name": "pen"}, {"name": "ink"}],
    "markup": "{{ name }}",
}


class LookupTest(unittest.TestCase):
    def test_keys_and_indexes(self):
        self.assertEqual(resolve(CTX, "user.address.city"), "Oslo")
        self.assertEqual(resolve(CTX, "items.1.name"), "ink")
        self.assertEqual(resolve(CTX, "user.tags.0"), "a")

    def test_falsy_values_are_found(self):
        self.assertEqual(resolve(CTX, "count"), 0)
        self.assertIs(resolve(CTX, "flag"), False)
        self.assertIsNone(resolve(CTX, "nothing"))
        self.assertEqual(resolve(CTX, "empty"), "")

    def test_missing_parts_raise_key_error(self):
        for path in ("zip", "user.zip", "items.2", "items.x", "items.-1", "name.length", "count.x", "", "user..address", "user."):
            with self.subTest(path=path):
                with self.assertRaises(KeyError):
                    resolve(CTX, path)


class RenderTest(unittest.TestCase):
    def test_plain_text_is_unchanged(self):
        self.assertEqual(render("Hello, world.", CTX), "Hello, world.")
        self.assertEqual(render("", CTX), "")

    def test_values(self):
        self.assertEqual(render("Hi {{ name }}!", CTX), "Hi Ada!")
        self.assertEqual(render("{{name}}/{{  user.address.city  }}", CTX), "Ada/Oslo")
        self.assertEqual(render("{{\n  items.0.name\n}}", CTX), "pen")

    def test_falsy_values_still_render(self):
        self.assertEqual(render("{{ count }}", CTX), "0")
        self.assertEqual(render("{{ flag }}", CTX), "False")
        self.assertEqual(render("[{{ nothing }}]", CTX), "[]")
        self.assertEqual(render("[{{ empty }}]", CTX), "[]")

    def test_missing_value_names_the_path(self):
        with self.assertRaises(TemplateError) as caught:
            render("Hi {{ user.phone }}", CTX)
        self.assertIn("user.phone", str(caught.exception))
        self.assertIsInstance(caught.exception, ValueError)

    def test_values_are_not_expanded_again(self):
        self.assertEqual(render("{{ markup }}", CTX), "{{ name }}")
        self.assertEqual(render("{{ markup }} and {{ name }}", CTX), "{{ name }} and Ada")
        self.assertEqual(render("{{ a }}{{ b }}", {"a": "{{", "b": "}}"}), "{{}}")

    def test_escaped_braces(self):
        self.assertEqual(render(r"\{{ name }}", CTX), "{{ name }}")
        self.assertEqual(render(r"\{{ {{ name }}", CTX), "{{ Ada")
        self.assertEqual(render(r"a \{{ b", CTX), "a {{ b")

    def test_stray_closing_braces_are_text(self):
        self.assertEqual(render("a }} b", CTX), "a }} b")

    def test_conditionals(self):
        self.assertEqual(render("{{#if name}}yes{{/if}}", CTX), "yes")
        self.assertEqual(render("[{{#if count}}x{{/if}}]", CTX), "[]")
        self.assertEqual(render("[{{#if flag}}x{{/if}}]", CTX), "[]")
        self.assertEqual(render("[{{#if empty}}x{{/if}}]", CTX), "[]")
        self.assertEqual(render("[{{#if missing.path}}x{{/if}}]", CTX), "[]")
        self.assertEqual(render("{{#if user.tags}}tags: {{ user.tags.1 }}{{/if}}", CTX), "tags: b")

    def test_nested_conditionals(self):
        tpl = "{{#if name}}A{{#if flag}}B{{/if}}C{{#if user}}D{{/if}}E{{/if}}F"
        self.assertEqual(render(tpl, CTX), "ACDEF")
        self.assertEqual(render(tpl, {"name": "", "flag": True, "user": 1}), "F")
        self.assertEqual(render(tpl, {"name": 1, "flag": True, "user": 0}), "ABCEF")

    def test_a_skipped_branch_does_not_need_its_values(self):
        self.assertEqual(render("{{#if missing}}{{ nope }}{{/if}}ok", CTX), "ok")

    def test_malformed_templates(self):
        for bad in (
            "{{ name",
            "Hi {{ name }} {{",
            "{{ a {{ b }}",
            "{{ }}",
            "{{#if}}x{{/if}}",
            "{{#if name}}x",
            "x{{/if}}",
            "{{#if name}}{{#if flag}}x{{/if}}",
        ):
            with self.subTest(template=bad):
                with self.assertRaises(TemplateError):
                    render(bad, CTX)


if __name__ == "__main__":
    unittest.main()
