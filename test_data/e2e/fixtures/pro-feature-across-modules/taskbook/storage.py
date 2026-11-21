"""JSON file persistence for tasks."""
import json
import os
from datetime import date

from .model import Task


class JsonStore:
    def __init__(self, path):
        self.path = path

    def load(self):
        if not os.path.exists(self.path):
            return []
        with open(self.path, encoding="utf-8") as fh:
            data = json.load(fh)
        tasks = []
        for item in data["tasks"]:
            due = date.fromisoformat(item["due"]) if item.get("due") else None
            tasks.append(Task(id=item["id"], title=item["title"], due=due, done=item.get("done", False)))
        return tasks

    def save(self, tasks):
        items = []
        for task in tasks:
            items.append({
                "id": task.id,
                "title": task.title,
                "due": task.due.isoformat() if task.due else None,
                "done": task.done,
            })
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump({"tasks": items}, fh, indent=2)
