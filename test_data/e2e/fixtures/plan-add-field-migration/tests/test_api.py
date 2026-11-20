import unittest

from app import api, storage


class CreateUserTest(unittest.TestCase):
    def test_creates_and_returns_user(self):
        conn = storage.connect()
        conn.executescript(open("migrations/001_create_users.sql").read())
        self.assertEqual(api.create_user(conn, {"id": 1, "name": "Ada"}), {"id": 1, "name": "Ada"})


if __name__ == "__main__":
    unittest.main()
