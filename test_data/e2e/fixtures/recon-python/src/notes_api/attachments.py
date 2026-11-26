import os
import posixpath

STORAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "storage")


def fetch_attachment(name):
    """Return (content, content type, status) for a stored attachment."""
    base = posixpath.basename(name)
    path = os.path.join(STORAGE, base)
    if not os.path.isfile(path):
        return "no such attachment: %s" % name, "text/plain", 404
    with open(path, encoding="utf-8") as handle:
        return handle.read(), "application/octet-stream", 200


def store_attachment(note_id, content):
    directory = os.path.join(STORAGE, str(note_id))
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, "%d.bin" % (abs(hash(content)) % 10 ** 9,))
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)
    return os.path.basename(path)
