import unittest

from app import api


class CreateUserTest(unittest.TestCase):
    def test_returns_the_email(self):
        self.assertEqual(api.create_user({"id": 1, "name": "Ada", "email": "ada@example.test"})["email"], "ada@example.test")

    def test_rejects_an_email_without_at(self):
        with self.assertRaises(ValueError):
            api.create_user({"id": 2, "name": "Bob", "email": "bob.example.test"})

    def test_still_requires_a_name(self):
        with self.assertRaises(ValueError):
            api.create_user({"id": 3, "email": "c@example.test"})


if __name__ == "__main__":
    unittest.main()
