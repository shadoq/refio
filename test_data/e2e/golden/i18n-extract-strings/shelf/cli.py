"""Command line front end of the shelf tool."""
import argparse
import json
import os
import sys

from .messages import t


def load(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def save(path, books):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(books, fh, indent=2)


def build_parser():
    parser = argparse.ArgumentParser(prog="shelf", description=t("prog.description"))
    sub = parser.add_subparsers(dest="cmd", required=True)
    add = sub.add_parser("add", help=t("help.add"))
    add.add_argument("title", help=t("help.add.title"))
    add.add_argument("author", help=t("help.add.author"))
    add.add_argument("--year", type=int, help=t("help.add.year"))
    ls = sub.add_parser("list", help=t("help.list"))
    ls.add_argument("--author", help=t("help.list.author"))
    rm = sub.add_parser("remove", help=t("help.remove"))
    rm.add_argument("number", type=int, help=t("help.remove.number"))
    sub.add_parser("stats", help=t("help.stats"))
    return parser


def describe(book):
    if book.get("year"):
        return t("book.with_year", title=book["title"], author=book["author"], year=book["year"])
    return t("book.no_year", title=book["title"], author=book["author"])


def main(argv, store, out=None, err=None):
    out = out or sys.stdout
    err = err or sys.stderr
    args = build_parser().parse_args(argv)
    books = load(store)

    if args.cmd == "add":
        if any(b["title"] == args.title and b["author"] == args.author for b in books):
            print(t("error.duplicate", title=args.title, author=args.author), file=err)
            return 1
        books.append({"title": args.title, "author": args.author, "year": args.year})
        save(store, books)
        print(t("added", book=describe(books[-1])), file=out)
        return 0

    if args.cmd == "list":
        if not books:
            print(t("shelf.empty"), file=out)
            return 0
        shown = [(i, b) for i, b in enumerate(books, 1) if not args.author or b["author"] == args.author]
        if not shown:
            print(t("list.none_by", author=args.author), file=out)
            return 0
        for number, book in shown:
            print(f"{number}. {book['title']} - {book['author']}", file=out)
        count = len(shown)
        print(t("list.count.one" if count == 1 else "list.count.many", count=count), file=out)
        return 0

    if args.cmd == "remove":
        if not 1 <= args.number <= len(books):
            print(t("error.no_such_number", number=args.number), file=err)
            return 1
        gone = books.pop(args.number - 1)
        save(store, books)
        print(t("removed", title=gone["title"]), file=out)
        return 0

    if not books:
        print(t("shelf.empty"), file=out)
        return 0
    print(t("stats.books", count=len(books)), file=out)
    print(t("stats.authors", count=len({b["author"] for b in books})), file=out)
    dated = [b for b in books if b.get("year")]
    if dated:
        oldest = min(dated, key=lambda b: b["year"])
        print(t("stats.oldest", title=oldest["title"], year=oldest["year"]), file=out)
    else:
        print(t("stats.oldest.unknown"), file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], "shelf.json"))
