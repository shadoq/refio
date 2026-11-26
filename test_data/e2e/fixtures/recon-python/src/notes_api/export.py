import os
import subprocess
import tempfile

CONVERTERS = {"pdf": "pandoc", "html": "pandoc", "txt": None}


def export_notes(owner, fmt):
    """Write the owner's notebook to a temporary file and convert it with the external tool."""
    if fmt not in CONVERTERS:
        raise ValueError("unsupported export format: %s" % fmt)
    markdown = "# Notebook of %s\n\n(no notes yet)\n" % owner
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as handle:
        handle.write(markdown)
        source = handle.name
    try:
        if CONVERTERS[fmt] is None:
            with open(source, encoding="utf-8") as handle:
                return handle.read()
        output = source[:-3] + "." + fmt
        command = "%s %s -o %s" % (CONVERTERS[fmt], source, output)
        subprocess.run(command, shell=True, check=True, capture_output=True)
        with open(output, "rb") as handle:
            return handle.read().decode("utf-8", "replace")
    finally:
        os.unlink(source)
        if os.path.exists(source[:-3] + "." + fmt):
            os.unlink(source[:-3] + "." + fmt)


def formats():
    return sorted(CONVERTERS)
