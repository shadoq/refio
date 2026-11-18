Our grade book module `grades.py` produces wrong results, and the course staff have started to complain. Students at exactly 90 get a B, and the class ranking looks off when two students have the same score. I also have the feeling that the weighted average is not right.

`tests/test_grades.py` shows what is expected (some of its tests fail right now). Find the bugs in `grades.py` and fix them so `python -m unittest discover -s tests` passes. Do not modify the tests, and do not rewrite the module from scratch - the functions and their docstrings describe the intended behaviour.
