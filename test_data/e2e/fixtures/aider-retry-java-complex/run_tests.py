"""Compile every .java file in this directory and run the plain test runner."""
import glob
import os
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
sources = sorted(glob.glob(os.path.join(here, "*.java")))
javac = shutil.which("javac")
java = shutil.which("java")
if not javac or not java:
    print("javac and java must be on PATH")
    sys.exit(2)

with tempfile.TemporaryDirectory() as out:
    compiled = subprocess.run(
        [javac, "-encoding", "UTF-8", "-d", out] + sources,
        capture_output=True, text=True, cwd=here,
    )
    if compiled.returncode != 0:
        print("COMPILATION FAILED")
        print(compiled.stdout)
        print(compiled.stderr)
        sys.exit(1)
    ran = subprocess.run([java, "-cp", out, "CalculatorTest"], cwd=here, timeout=60)
    sys.exit(ran.returncode)
