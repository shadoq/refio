"""Small string helpers."""

_SMALL = {"a", "an", "the", "of", "in", "on", "and", "or"}


def compress_runs(s: str) -> str:
    out = []
    i = 0
    while i < len(s):
        j = i
        while j < len(s) and s[j] == s[i]:
            j += 1
        n = j - i
        out.append(s[i] + (str(n) if n > 1 else ""))
        i = j
    return "".join(out)


def title_case_words(s: str) -> str:
    words = s.split(" ") if s else []
    out = []
    for idx, w in enumerate(words):
        lw = w.lower()
        if idx > 0 and lw in _SMALL:
            out.append(lw)
        else:
            out.append(lw[:1].upper() + lw[1:])
    return " ".join(out)


def count_vowel_groups(s: str) -> int:
    count = 0
    prev = False
    for ch in s.lower():
        v = ch in "aeiou"
        if v and not prev:
            count += 1
        prev = v
    return count


def mask_digits(s: str, keep: int) -> str:
    total = sum(ch.isdigit() for ch in s)
    seen = 0
    out = []
    for ch in s:
        if ch.isdigit():
            seen += 1
            out.append(ch if seen > total - keep else "*")
        else:
            out.append(ch)
    return "".join(out)


def rotate_words(s: str, k: int) -> str:
    words = s.split()
    if not words:
        return ""
    k = max(k, 0) % len(words)
    return " ".join(words[k:] + words[:k])
