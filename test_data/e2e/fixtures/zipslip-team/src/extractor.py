"""Archive extraction for user uploads."""
import os
import zipfile


def extract_archive(zip_path: str, dest: str) -> list:
    """Extract every entry of the archive into dest. Returns the written paths."""
    written = []
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            target = os.path.join(dest, name)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with zf.open(name) as src, open(target, "wb") as out:
                out.write(src.read())
            written.append(target)
    return written
