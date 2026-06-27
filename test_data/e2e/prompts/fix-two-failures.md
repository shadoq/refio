Running `python3 main.py` fails on its assertions. There are two independent bugs, each in a
different file under `src/`:

- `reverse_words` in `src/strings.py` should return the words in reversed order
  (`reverse_words("hello world foo") == "foo world hello"`).
- `average` in `src/numbers.py` should return a true average
  (`average([1, 2]) == 1.5`, not `1`).

Find and fix the root cause in each file so all assertions pass. Do NOT edit `main.py` or weaken
the assertions. When done, `python3 main.py` must print `OK`.
