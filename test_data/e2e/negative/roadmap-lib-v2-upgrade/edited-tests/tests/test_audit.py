import unittest

from app.audit import audit_file
from helpers import write_tmp


class AuditTest(unittest.TestCase):
    def test_clean_file(self):
        path = write_tmp(self, "a.csv", "id,qty\n1,5\n2,7\n")
        self.assertEqual(audit_file(path), [])


if __name__ == "__main__":
    unittest.main()
