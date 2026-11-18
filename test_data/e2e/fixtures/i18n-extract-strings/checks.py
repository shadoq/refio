"""Acceptance checks for the message extraction. Run: python checks.py"""
import ast
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

# Every piece of user-facing wording that used to live in shelf/cli.py.
FRAGMENTS = [
    "Manage a tiny personal library", "Add a book", "Title of the book", "Author of the book",
    "Year of publication", "List the books", "Only show books", "Remove a book", "Number shown by",
    "Show statistics", "Error", "already on the shelf", "Added", "The shelf is empty", "No books by",
    "listed", " by ", "no book number", "Removed", "Books:", "Authors:", "Oldest",
]
# Words that must not stay behind as bare literals either (plural handling).
BARE_WORDS = {"book", "books"}


def main():
    ok = True
    suite = unittest.defaultTestLoader.discover(os.path.join(ROOT, "tests"), top_level_dir=ROOT)
    if not unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful():
        print("FAIL: behaviour changed, the CLI output is no longer identical")
        ok = False

    msg_path = os.path.join(ROOT, "shelf", "messages.py")
    if not os.path.isfile(msg_path):
        print("FAIL: shelf/messages.py does not exist")
        return 1
    with open(msg_path, encoding="utf-8") as fh:
        catalog = fh.read()
    for frag in FRAGMENTS + sorted(BARE_WORDS):
        if frag not in catalog:
            print(f"FAIL: the catalog has no text containing {frag!r}")
            ok = False

    with open(os.path.join(ROOT, "shelf", "cli.py"), encoding="utf-8") as fh:
        source = fh.read()
    tree = ast.parse(source)
    imports_catalog = any(
        (isinstance(n, ast.ImportFrom) and (n.module or "").endswith("messages"))
        or (isinstance(n, ast.ImportFrom) and any(a.name == "messages" for a in n.names))
        or (isinstance(n, ast.Import) and any(a.name.endswith("messages") for a in n.names))
        for n in ast.walk(tree)
    )
    if not imports_catalog:
        print("FAIL: shelf/cli.py does not import the messages module")
        ok = False
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and node.value in BARE_WORDS:
            print(f"FAIL: shelf/cli.py line {node.lineno} still has the bare word {node.value!r}")
            ok = False
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            for frag in FRAGMENTS:
                if frag in node.value:
                    print(f"FAIL: shelf/cli.py line {node.lineno} still has message text {node.value!r}")
                    ok = False
                    break
    print("OK" if ok else "NOT OK")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
