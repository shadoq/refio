"""`python3 -m vault ...` entry point: hands the command line to vault.cli."""
import sys

from vault.cli import main

main(sys.argv[1:])
