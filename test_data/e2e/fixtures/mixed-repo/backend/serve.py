"""Minimal file-download backend (stdlib only)."""

import os

BASE_DIR = "/srv/files"


def list_files():
    return os.listdir(BASE_DIR)


def download_file(filename):
    path = os.path.join(BASE_DIR, filename)
    with open(path, "rb") as handle:
        return handle.read()
