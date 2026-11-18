`strutils.py` has five empty functions. Please implement them. What each one should do:

1. `compress_runs(s)` - run-length encode a string. Each run of the same character becomes the character followed by the run length, but a run of length 1 gets no number. Comparison is case sensitive and every character (spaces too) counts. `"aaabccdd"` becomes `"a3bc2d2"`.
2. `title_case_words(s)` - capitalise every word in a space separated string and lowercase the rest of each word. The small words "a", "an", "the", "of", "in", "on", "and", "or" stay lowercase, except when they are the first word. Keep the single spaces as they are.
3. `count_vowel_groups(s)` - count the maximal runs of consecutive vowels (a, e, i, o, u, any case; y is not a vowel). `"beautiful"` has 3 groups.
4. `mask_digits(s, keep)` - replace every digit with `*` except the last `keep` digits of the string. Other characters stay as they are. If there are fewer digits than `keep`, nothing is masked.
5. `rotate_words(s, k)` - split the string on any whitespace and rotate the list of words left by `k` positions. `k` can be negative (rotate right) or larger than the number of words. Return the words joined by single spaces, and an empty string when there are no words.

Run `python -m unittest discover -s tests` when you are done. Do not change anything under `tests/`.
