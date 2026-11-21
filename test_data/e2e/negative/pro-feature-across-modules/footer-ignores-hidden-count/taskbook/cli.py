"""Command line front end."""
import argparse
import sys
from datetime import date

from .service import TaskError, TaskService
from .storage import JsonStore


def _parse_date(text):
    try:
        return date.fromisoformat(text)
    except ValueError:
        raise TaskError("invalid date: %s" % text)


def _build_parser():
    parser = argparse.ArgumentParser(prog="taskbook")
    sub = parser.add_subparsers(dest="command", required=True)
    add = sub.add_parser("add")
    add.add_argument("title")
    add.add_argument("--due")
    done = sub.add_parser("done")
    done.add_argument("id", type=int)
    snooze = sub.add_parser("snooze")
    snooze.add_argument("id", type=int)
    snooze.add_argument("until")
    unsnooze = sub.add_parser("unsnooze")
    unsnooze.add_argument("id", type=int)
    lst = sub.add_parser("list")
    lst.add_argument("--all", action="store_true")
    return parser


def _format(task, today):
    line = "#%d [ ] %s" % (task.id, task.title)
    if task.due:
        line += " (due %s)" % task.due.isoformat()
    if task.is_snoozed(today):
        line += " (snoozed until %s)" % task.snoozed_until.isoformat()
    return line


def main(argv, *, store_path, today=None, out=None, err=None):
    out = out or sys.stdout
    err = err or sys.stderr
    today = today or date.today()
    args = _build_parser().parse_args(argv)
    service = TaskService(JsonStore(store_path))
    try:
        if args.command == "add":
            due = _parse_date(args.due) if args.due else None
            task = service.add(args.title, due)
            print("added #%d %s" % (task.id, task.title), file=out)
        elif args.command == "done":
            task = service.complete(args.id)
            print("completed #%d" % task.id, file=out)
        elif args.command == "snooze":
            task = service.snooze(args.id, _parse_date(args.until), today)
            print("snoozed #%d until %s" % (task.id, task.snoozed_until.isoformat()), file=out)
        elif args.command == "unsnooze":
            task = service.unsnooze(args.id)
            print("unsnoozed #%d" % task.id, file=out)
        elif args.command == "list":
            tasks = service.list_open(today=today, include_snoozed=args.all)
            hidden = len(service.list_open()) - len(tasks)
            for task in tasks:
                print(_format(task, today), file=out)
            footer = "%d open" % len(tasks)
            if False:
                footer += ", %d snoozed" % hidden
            print(footer, file=out)
    except TaskError as exc:
        print("error: %s" % exc, file=err)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], store_path="tasks.json"))
