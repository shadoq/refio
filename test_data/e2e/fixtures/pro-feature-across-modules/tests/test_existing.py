import io
import json
import os
import tempfile
import unittest
from datetime import date

from taskbook.cli import main
from taskbook.service import TaskError, TaskService
from taskbook.storage import JsonStore


class ExistingBehaviourTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "tasks.json")

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        code = main(list(argv), store_path=self.path, today=date(2025, 3, 1), out=out, err=err)
        return code, out.getvalue(), err.getvalue()

    def test_add_and_list_orders_by_due_then_id(self):
        self.run_cli("add", "late")
        self.run_cli("add", "soon", "--due", "2025-03-05")
        self.run_cli("add", "sooner", "--due", "2025-03-02")
        code, out, _ = self.run_cli("list")
        self.assertEqual(code, 0)
        self.assertEqual(out, "#3 [ ] sooner (due 2025-03-02)\n#2 [ ] soon (due 2025-03-05)\n#1 [ ] late\n3 open\n")

    def test_done_removes_from_list(self):
        self.run_cli("add", "a")
        self.run_cli("add", "b")
        self.assertEqual(self.run_cli("done", "1")[1], "completed #1\n")
        self.assertEqual(self.run_cli("list")[1], "#2 [ ] b\n1 open\n")

    def test_unknown_task_is_an_error(self):
        code, _, err = self.run_cli("done", "9")
        self.assertEqual((code, err), (1, "error: no such task 9\n"))

    def test_empty_title_rejected(self):
        with self.assertRaises(TaskError):
            TaskService(JsonStore(self.path)).add("   ")

    def test_loads_file_written_by_older_version(self):
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump({"tasks": [{"id": 4, "title": "legacy", "due": None, "done": False}]}, fh)
        self.assertEqual([t.title for t in TaskService(JsonStore(self.path)).list_open()], ["legacy"])


if __name__ == "__main__":
    unittest.main()
