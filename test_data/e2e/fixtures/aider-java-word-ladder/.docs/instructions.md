# Instructions

Find every shortest word ladder between two words. Implement `WordLadder.shortestLadders` in `src/WordLadder.java`.

A **ladder** is a list of words that starts with `start`, ends with `end`, and in which each word differs from the previous one in exactly one position (same length, one letter replaced; letters are never added, removed or moved). Every word of the ladder except the first must be in the dictionary. The first word `start` does **not** have to be in the dictionary.

`shortestLadders(String start, String end, Collection<String> dictionary)` returns all ladders that have the smallest possible number of words, as a `List<List<String>>`:

- The ladders are sorted lexicographically: compare them word by word with `String.compareTo`, and the first difference decides. No ladder appears twice.
- When there is no ladder, the result is an empty list.
- If `start` equals `end` the only ladder is the one-word ladder `[start]`, even when the word is not in the dictionary.
- When `end` is not in the dictionary (and differs from `start`) there is no ladder. When `start` and `end` have different lengths there is no ladder either.
- Comparison is case-sensitive. Words in the dictionary whose length differs from `start` are ignored, and so are duplicates in the dictionary.
- A ladder never visits a word twice. In particular it never goes back through `start` when `start` also happens to be in the dictionary.

The dictionary can hold thousands of words and the answer can hold hundreds of ladders, so enumerating every possible path and keeping the shortest ones is too slow. The input collection must not be modified.
