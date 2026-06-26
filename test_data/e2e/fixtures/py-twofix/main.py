from src.strings import reverse_words
from src.numbers import average

assert reverse_words("hello world foo") == "foo world hello", reverse_words("hello world foo")
assert average([1, 2]) == 1.5, average([1, 2])
assert average([2, 4, 6]) == 4.0, average([2, 4, 6])

print("OK")
