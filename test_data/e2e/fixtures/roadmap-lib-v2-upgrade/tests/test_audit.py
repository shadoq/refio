import unittest

from app.audit import audit_file
from helpers import write_tmp


class AuditTest(unittest.TestCase):
    def test_clean_file(self):
        path = write_tmp(self, "a.csv", "id,qty\n1,5\n2,7\n")
        self.assertEqual(audit_file(path), [])

    def test_empty_fields_are_reported_with_data_row_numbers(self):
        path = write_tmp(self, "a.csv", "id,qty\n1,5\n,7\n3,\n")
        self.assertEqual(audit_file(path), ["row 2: missing field", "row 3: missing field"])

    def test_whitespace_only_field_counts_as_missing(self):
        path = write_tmp(self, "a.csv", "id,qty\n1, \n")
        self.assertEqual(audit_file(path), ["row 1: missing field"])

    def test_broken_row_reports_only_that_row(self):
        path = write_tmp(self, "a.csv", "id,qty\n,7\n2,5\n3,1,9\n4,4\n")
        self.assertEqual(audit_file(path), ["row 3: expected 2 fields, got 3"])


if __name__ == "__main__":
    unittest.main()
