import glob
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    kotlinc = shutil.which("kotlinc")
    java = shutil.which("java")
    if not kotlinc or not java:
        print("kotlinc and java must be on PATH")
        return 2
    sources = sorted(glob.glob(os.path.join(HERE, "src", "**", "*.kt"), recursive=True))
    with tempfile.TemporaryDirectory() as out:
        jar = os.path.join(out, "out.jar")
        compiled = subprocess.run([kotlinc, *sources, "-include-runtime", "-d", jar], cwd=HERE)
        if compiled.returncode != 0:
            print("kotlin compilation failed")
            return 1
        return subprocess.run([java, "-jar", jar], cwd=HERE).returncode


if __name__ == "__main__":
    sys.exit(main())
