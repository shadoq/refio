The trip reporter in this workspace does not run. `python reporter/start.py` fails, and the owner of the library says it "should just work from source". Please make the setup work.

There is no network on this machine, so pip and any download are out of the question: the library is used straight from its directory. Fix the setup itself and leave the reporter application code, its requirement file, the sample data and the library's `pyproject.toml` alone, since those are right as they are. The start script should work no matter which directory it is launched from.

`python check.py` must print OK. Do not edit `check.py` or `README.md`.
