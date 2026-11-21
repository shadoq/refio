import os
import shutil
import tempfile


def tmp_dir(test):
    folder = tempfile.mkdtemp()
    test.addCleanup(shutil.rmtree, folder, True)
    return folder


def write_tmp(test, name, text):
    path = os.path.join(tmp_dir(test), name)
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)
    return path


def out_path(test, name):
    return os.path.join(tmp_dir(test), name)


def read_tmp(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return fh.read()
