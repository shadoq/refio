"""Task use cases."""
from .model import Task


class TaskError(Exception):
    pass


class TaskService:
    def __init__(self, store):
        self._store = store
        self._tasks = store.load()

    def _get(self, task_id):
        for task in self._tasks:
            if task.id == task_id:
                return task
        raise TaskError("no such task %d" % task_id)

    def add(self, title, due=None):
        if not title.strip():
            raise TaskError("title must not be empty")
        next_id = max((t.id for t in self._tasks), default=0) + 1
        task = Task(id=next_id, title=title.strip(), due=due)
        self._tasks.append(task)
        self._store.save(self._tasks)
        return task

    def complete(self, task_id):
        task = self._get(task_id)
        task.done = True
        task.snoozed_until = None
        self._store.save(self._tasks)
        return task

    def snooze(self, task_id, until, today):
        task = self._get(task_id)
        if task.done:
            raise TaskError("task %d is already done" % task_id)
        if until <= today:
            raise TaskError("snooze date must be after today")
        task.snoozed_until = until
        self._store.save(self._tasks)
        return task

    def unsnooze(self, task_id):
        task = self._get(task_id)
        task.snoozed_until = None
        self._store.save(self._tasks)
        return task

    def list_open(self, today=None, include_snoozed=False):
        """Open tasks, soonest due date first, tasks without a due date last, ties by id.

        Without `today` nothing is hidden. With it, tasks snoozed past that day are left out
        unless include_snoozed is set.
        """
        open_tasks = [t for t in self._tasks if not t.done]
        if today is not None and not include_snoozed:
            open_tasks = [t for t in open_tasks if not t.is_snoozed(today)]
        return sorted(open_tasks, key=lambda t: (t.due is None, t.due.toordinal() if t.due else 0, t.id))
