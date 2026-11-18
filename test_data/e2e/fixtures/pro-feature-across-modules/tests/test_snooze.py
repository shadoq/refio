import io
import json
import os
import tempfile
import unittest
from datetime import date

from taskbook.cli import main
from taskbook.service import TaskError, TaskService
from taskbook.storage import JsonStore

TODAY = date(2025, 3, 1)


class SnoozeCliTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "tasks.json")

    def cli(self, *argv, today=TODAY):
        out, err = io.StringIO(), io.StringIO()
        code = main(list(argv), store_path=self.path, today=today, out=out, err=err)
        return code, out.getvalue(), err.getvalue()

    def seed(self):
        self.cli("add", "alpha", "--due", "2025-03-03")
        self.cli("add", "beta")
        self.cli("add", "gamma", "--due", "2025-03-02")

    def test_snoozed_task_is_hidden_and_counted(self):
        self.seed()
        self.assertEqual(self.cli("snooze", "3", "2025-03-04"), (0, "snoozed #3 until 2025-03-04\n", ""))
        self.assertEqual(self.cli("list")[1], "#1 [ ] alpha (due 2025-03-03)\n#2 [ ] beta\n2 open, 1 snoozed\n")

    def test_list_all_shows_snoozed_with_marker_in_normal_order(self):
        self.seed()
        self.cli("snooze", "3", "2025-03-04")
        out = self.cli("list", "--all")[1]
        self.assertEqual(out, "#3 [ ] gamma (due 2025-03-02) (snoozed until 2025-03-04)\n"
                              "#1 [ ] alpha (due 2025-03-03)\n#2 [ ] beta\n3 open\n")

    def test_snooze_without_due_date_marker(self):
        self.seed()
        self.cli("snooze", "2", "2025-03-09")
        self.assertIn("#2 [ ] beta (snoozed until 2025-03-09)\n", self.cli("list", "--all")[1])

    def test_snooze_ends_on_the_snooze_date(self):
        self.seed()
        self.cli("snooze", "2", "2025-03-05")
        self.assertNotIn("beta", self.cli("list", today=date(2025, 3, 4))[1])
        code, out, _ = self.cli("list", today=date(2025, 3, 5))
        self.assertIn("#2 [ ] beta\n", out)
        self.assertTrue(out.endswith("3 open\n"))
        self.assertNotIn("snoozed until", self.cli("list", "--all", today=date(2025, 3, 5))[1])

    def test_snooze_date_must_be_after_today(self):
        self.seed()
        self.assertEqual(self.cli("snooze", "1", "2025-03-01"), (1, "", "error: snooze date must be after today\n"))
        self.assertEqual(self.cli("snooze", "1", "2025-02-20")[0], 1)
        self.assertEqual(self.cli("list")[1].count("snoozed"), 0)

    def test_invalid_date_and_unknown_task(self):
        self.seed()
        self.assertEqual(self.cli("snooze", "1", "next-week"), (1, "", "error: invalid date: next-week\n"))
        self.assertEqual(self.cli("snooze", "9", "2025-03-04"), (1, "", "error: no such task 9\n"))
        self.assertEqual(self.cli("unsnooze", "9"), (1, "", "error: no such task 9\n"))

    def test_done_task_cannot_be_snoozed(self):
        self.seed()
        self.cli("done", "1")
        self.assertEqual(self.cli("snooze", "1", "2025-03-04"), (1, "", "error: task 1 is already done\n"))

    def test_unsnooze_makes_task_visible_and_is_idempotent(self):
        self.seed()
        self.cli("snooze", "2", "2025-03-09")
        self.assertEqual(self.cli("unsnooze", "2"), (0, "unsnoozed #2\n", ""))
        self.assertEqual(self.cli("unsnooze", "2"), (0, "unsnoozed #2\n", ""))
        self.assertEqual(self.cli("unsnooze", "1"), (0, "unsnoozed #1\n", ""))
        self.assertTrue(self.cli("list")[1].endswith("3 open\n"))

    def test_completing_a_snoozed_task_clears_the_snooze(self):
        self.seed()
        self.cli("snooze", "2", "2025-03-09")
        self.cli("done", "2")
        task = [t for t in JsonStore(self.path).load() if t.id == 2][0]
        self.assertTrue(task.done)
        self.assertIsNone(task.snoozed_until)

    def test_snooze_persists_as_iso_string(self):
        self.seed()
        self.cli("snooze", "1", "2025-03-04")
        with open(self.path, encoding="utf-8") as fh:
            stored = {item["id"]: item for item in json.load(fh)["tasks"]}
        self.assertEqual(stored[1]["snoozed_until"], "2025-03-04")
        self.assertIsNone(stored[2].get("snoozed_until"))
        self.assertEqual(JsonStore(self.path).load()[0].snoozed_until, date(2025, 3, 4))


class SnoozeServiceTest(unittest.TestCase):
    def setUp(self):
        path = os.path.join(tempfile.mkdtemp(), "tasks.json")
        self.store = JsonStore(path)
        self.service = TaskService(self.store)
        self.a = self.service.add("a")
        self.b = self.service.add("b")

    def test_list_open_without_today_keeps_returning_everything(self):
        self.service.snooze(self.a.id, date(2025, 3, 4), TODAY)
        self.assertEqual([t.id for t in self.service.list_open()], [1, 2])

    def test_list_open_hides_snoozed_when_today_given(self):
        self.service.snooze(self.a.id, date(2025, 3, 4), TODAY)
        self.assertEqual([t.id for t in self.service.list_open(today=TODAY)], [2])
        self.assertEqual([t.id for t in self.service.list_open(today=TODAY, include_snoozed=True)], [1, 2])
        self.assertEqual([t.id for t in self.service.list_open(today=date(2025, 3, 4))], [1, 2])

    def test_snooze_rejects_same_day_and_done(self):
        with self.assertRaisesRegex(TaskError, "snooze date must be after today"):
            self.service.snooze(self.a.id, TODAY, TODAY)
        self.service.complete(self.b.id)
        with self.assertRaisesRegex(TaskError, "task 2 is already done"):
            self.service.snooze(self.b.id, date(2025, 3, 9), TODAY)

    def test_snooze_survives_reload(self):
        self.service.snooze(self.a.id, date(2025, 3, 4), TODAY)
        reloaded = TaskService(JsonStore(self.store.path))
        self.assertEqual([t.id for t in reloaded.list_open(today=TODAY)], [2])


if __name__ == "__main__":
    unittest.main()
