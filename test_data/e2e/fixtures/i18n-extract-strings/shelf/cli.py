"""Command line front end of the shelf tool."""
import argparse
import json
import os
import sys


def load(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def save(path, books):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(books, fh, indent=2)


def build_parser():
    parser = argparse.ArgumentParser(prog="shelf", description="Manage a tiny personal library.")
    sub = parser.add_subparsers(dest="cmd", required=True)
    add = sub.add_parser("add", help="Add a book to the shelf")
    add.add_argument("title", help="Title of the book")
    add.add_argument("author", help="Author of the book")
    add.add_argument("--year", type=int, help="Year of publication")
    ls = sub.add_parser("list", help="List the books on the shelf")
    ls.add_argument("--author", help="Only show books by this author")
    rm = sub.add_parser("remove", help="Remove a book by its number")
    rm.add_argument("number", type=int, help="Number shown by the list command")
    sub.add_parser("stats", help="Show statistics about the shelf")
    return parser


def describe(book):
    if book.get("year"):
        return f'"{book["title"]}" by {book["author"]} ({book["year"]})'
    return f'"{book["title"]}" by {book["author"]}'


def main(argv, store, out=None, err=None):
    out = out or sys.stdout
    err = err or sys.stderr
    args = build_parser().parse_args(argv)
    books = load(store)

    if args.cmd == "add":
        if any(b["title"] == args.title and b["author"] == args.author for b in books):
            print(f'Error: "{args.title}" by {args.author} is already on the shelf.', file=err)
            return 1
        books.append({"title": args.title, "author": args.author, "year": args.year})
        save(store, books)
        print(f"Added {describe(books[-1])}.", file=out)
        return 0

    if args.cmd == "list":
        if not books:
            print("The shelf is empty.", file=out)
            return 0
        shown = [(i, b) for i, b in enumerate(books, 1) if not args.author or b["author"] == args.author]
        if not shown:
            print(f"No books by {args.author}.", file=out)
            return 0
        for number, book in shown:
            print(f"{number}. {book['title']} - {book['author']}", file=out)
        count = len(shown)
        noun = "book" if count == 1 else "books"
        print(f"{count} {noun} listed.", file=out)
        return 0

    if args.cmd == "remove":
        if not 1 <= args.number <= len(books):
            print(f"Error: there is no book number {args.number}.", file=err)
            return 1
        gone = books.pop(args.number - 1)
        save(store, books)
        print(f'Removed "{gone["title"]}".', file=out)
        return 0

    if not books:
        print("The shelf is empty.", file=out)
        return 0
    print(f"Books: {len(books)}", file=out)
    print(f"Authors: {len({b['author'] for b in books})}", file=out)
    dated = [b for b in books if b.get("year")]
    if dated:
        oldest = min(dated, key=lambda b: b["year"])
        print(f"Oldest: {oldest['title']} ({oldest['year']})", file=out)
    else:
        print("Oldest: unknown", file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], "shelf.json"))
