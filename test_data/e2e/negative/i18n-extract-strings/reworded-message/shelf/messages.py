"""Message catalog: every user-facing string of the shelf tool."""

MESSAGES = {
    "prog.description": "Manage a tiny personal library.",
    "help.add": "Add a book to the shelf",
    "help.add.title": "Title of the book",
    "help.add.author": "Author of the book",
    "help.add.year": "Year of publication",
    "help.list": "List the books on the shelf",
    "help.list.author": "Only show books by this author",
    "help.remove": "Remove a book by its number",
    "help.remove.number": "Number shown by the list command",
    "help.stats": "Show statistics about the shelf",
    "book.with_year": '"{title}" by {author} ({year})',
    "book.no_year": '"{title}" by {author}',
    "error.duplicate": 'Error: "{title}" by {author} is already on the shelf.',
    "error.no_such_number": "Error: there is no book number {number}.",
    "added": "Added: {book}.",
    "shelf.empty": "The shelf is empty.",
    "list.none_by": "No books by {author}.",
    "list.count.one": "{count} book listed.",
    "list.count.many": "{count} books listed.",
    "removed": 'Removed "{title}".',
    "stats.books": "Books: {count}",
    "stats.authors": "Authors: {count}",
    "stats.oldest": "Oldest: {title} ({year})",
    "stats.oldest.unknown": "Oldest: unknown",
}


def t(key, **values):
    return MESSAGES[key].format(**values)
