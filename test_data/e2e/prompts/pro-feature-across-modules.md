Please implement the ticket below in the `taskbook` package. It touches the model, the storage, the service and the CLI. Existing behaviour and the existing tests in `tests/test_existing.py` must keep working, and `tests/test_snooze.py` holds the acceptance tests for this ticket. Do not edit anything under `tests/`.

---

TB-212: Snooze a task

As a user with a long list I want to hide a task until a date, so that I only see what I can act on today.

Acceptance criteria

1. `taskbook snooze <id> <YYYY-MM-DD>` hides the task from `taskbook list` until that date. On the snooze date itself the task is visible again, and a lapsed snooze is treated exactly like no snooze at all. On success it prints `snoozed #<id> until <date>`.
2. The date must be strictly after today. A task that is already done cannot be snoozed. Failures print one line to stderr and exit with code 1: `error: snooze date must be after today`, `error: task <id> is already done`, `error: invalid date: <text>`, `error: no such task <id>`.
3. `taskbook unsnooze <id>` clears the snooze and prints `unsnoozed #<id>`. Unsnoozing a task that is not snoozed is a no-op, not an error.
4. `taskbook list --all` also shows snoozed tasks, in the normal order, with ` (snoozed until <date>)` appended after the due part.
5. The footer of `list` counts the tasks that are shown: `<n> open`. When some open tasks are hidden by a snooze it reads `<n> open, <m> snoozed`.
6. Completing a task clears its snooze.
7. The snooze date is stored in the JSON file as an ISO string under `snoozed_until`. Files written by older versions (no such key) must still load.
8. Service API: `TaskService.snooze(task_id, until, today)`, `TaskService.unsnooze(task_id)` and `TaskService.list_open(today=None, include_snoozed=False)`. Calling `list_open()` without `today` keeps returning every open task, as it does now.
