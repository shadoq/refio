import glob
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        print("javac and java must be on PATH")
        return 2
    sources = sorted(glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True))
    sources += sorted(glob.glob(os.path.join(HERE, "test", "**", "*.java"), recursive=True))
    with tempfile.TemporaryDirectory() as out:
        compiled = subprocess.run([javac, "-d", out, *sources], cwd=HERE)
        if compiled.returncode != 0:
            print("compilation failed")
            return 1
        return subprocess.run([java, "-cp", out, "BookingTests"], cwd=HERE).returncode


if __name__ == "__main__":
    sys.exit(main())
