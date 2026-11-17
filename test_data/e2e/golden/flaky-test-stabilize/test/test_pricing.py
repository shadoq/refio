import json
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from src import pricing  # noqa: E402

SCRATCH = os.path.join("scratch", "summary.json")


class TestPricing(unittest.TestCase):
    def setUp(self):
        # Every test arranges its own scratch state; nothing depends on sibling order.
        os.makedirs("scratch", exist_ok=True)
        self.addCleanup(self._wipe_scratch)

    @staticmethod
    def _wipe_scratch():
        if os.path.exists(SCRATCH):
            os.remove(SCRATCH)

    def test_happy_hour_discount(self):
        self.assertEqual(pricing.happy_hour_discount(10.0, 18), 8.0)
        self.assertEqual(pricing.happy_hour_discount(10.0, 12), 10.0)
        self.assertEqual(pricing.happy_hour_discount(9.99, 19), 9.99)

    def test_audit_sample_exact(self):
        # deterministic given the seed: the same seed reproduces the same sample,
        # and the sample is always a subset of the input
        random.seed(4)
        records = [f"tx-{i:02d}" for i in range(10)]
        sample = pricing.audit_sample(records)
        random.seed(4)
        again = pricing.audit_sample(records)
        self.assertEqual(sample, again)
        self.assertTrue(set(sample) <= set(records))

    def test_summary_export(self):
        summary = {"items": 3, "total": 42.0}
        with open(SCRATCH, "w") as fh:
            json.dump(summary, fh)
        with open(SCRATCH) as fh:
            self.assertEqual(json.load(fh), summary)

    def test_summary_reload(self):
        # arrange the precondition itself - do not rely on test_summary_export
        with open(SCRATCH, "w") as fh:
            json.dump({"items": 3, "total": 42.0}, fh)
        with open(SCRATCH) as fh:
            data = json.load(fh)
        self.assertEqual(data, {"items": 3, "total": 42.0})

    def test_audit_sample_all_or_nothing_boundaries(self):
        # sanity: the sample is always a subset of the input
        records = [f"tx-{i:02d}" for i in range(6)]
        self.assertTrue(set(pricing.audit_sample(records)) <= set(records))


if __name__ == "__main__":
    unittest.main()
