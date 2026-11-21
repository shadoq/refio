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
    sub.add_parser("list")
    return parser


def _format(task):
    line = "#%d [ ] %s" % (task.id, task.title)
    if task.due:
        line += " (due %s)" % task.due.isoformat()
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
        elif args.command == "list":
            tasks = service.list_open()
            for task in tasks:
                print(_format(task), file=out)
            print("%d open" % len(tasks), file=out)
    except TaskError as exc:
        print("error: %s" % exc, file=err)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], store_path="tasks.json"))
