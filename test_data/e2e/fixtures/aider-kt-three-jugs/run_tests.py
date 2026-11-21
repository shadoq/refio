"""Compile every .kt file in this directory into one jar and run its main()."""
import glob
import os
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
sources = sorted(glob.glob(os.path.join(here, "*.kt")))
kotlinc = shutil.which("kotlinc")
java = shutil.which("java")
if not kotlinc or not java:
    print("kotlinc and java must be on PATH")
    sys.exit(2)

with tempfile.TemporaryDirectory() as out:
    jar = os.path.join(out, "out.jar")
    compiled = subprocess.run(
        [kotlinc, "-nowarn", "-include-runtime", "-d", jar] + sources,
        capture_output=True, text=True, cwd=here,
    )
    if compiled.returncode != 0:
        print("COMPILATION FAILED")
        print(compiled.stdout)
        print(compiled.stderr)
        sys.exit(1)
    ran = subprocess.run([java, "-jar", jar], cwd=here, timeout=120)
    sys.exit(ran.returncode)
