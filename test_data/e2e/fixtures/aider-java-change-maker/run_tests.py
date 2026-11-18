"""Compile src and test with javac and run the plain-Java test runner."""
import glob
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "build")


def main():
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        print("javac/java not found on PATH")
        return 2
    sources = glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True)
    sources += glob.glob(os.path.join(HERE, "test", "**", "*.java"), recursive=True)
    os.makedirs(OUT, exist_ok=True)
    compile_result = subprocess.run([javac, "-encoding", "UTF-8", "-d", OUT] + sources, cwd=HERE)
    if compile_result.returncode != 0:
        return compile_result.returncode
    run = subprocess.run([java, "-cp", OUT, "ChangeMakerTest"], cwd=HERE)
    return run.returncode


if __name__ == "__main__":
    sys.exit(main())
