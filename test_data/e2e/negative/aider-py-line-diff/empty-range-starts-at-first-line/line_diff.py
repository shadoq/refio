"""Line diff based on a longest common subsequence."""


def _edit_script(old, new):
    n, m = len(old), len(new)
    lcs = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            if old[i] == new[j]:
                lcs[i][j] = lcs[i + 1][j + 1] + 1
            else:
                lcs[i][j] = max(lcs[i + 1][j], lcs[i][j + 1])

    ops = []
    i = j = 0
    while i < n or j < m:
        if i < n and j < m and old[i] == new[j]:
            ops.append((" ", old[i]))
            i += 1
            j += 1
        elif i < n and (j == m or lcs[i + 1][j] >= lcs[i][j + 1]):
            ops.append(("-", old[i]))
            i += 1
        else:
            ops.append(("+", new[j]))
            j += 1
    return ops


def diff(old, new, context=1):
    """Return the unified-diff-like hunks that turn `old` into `new`."""
    if context < 0:
        raise ValueError("context must not be negative")
    ops = _edit_script(list(old), list(new))

    old_before = [0]
    new_before = [0]
    for tag, _ in ops:
        old_before.append(old_before[-1] + (tag != "+"))
        new_before.append(new_before[-1] + (tag != "-"))

    changes = [k for k, (tag, _) in enumerate(ops) if tag != " "]
    if not changes:
        return []

    groups = [[changes[0], changes[0]]]
    for k in changes[1:]:
        if k - groups[-1][1] - 1 <= 2 * context:
            groups[-1][1] = k
        else:
            groups.append([k, k])

    out = []
    for first, last in groups:
        start = max(0, first - context)
        end = min(len(ops), last + context + 1)
        part = ops[start:end]
        old_count = sum(1 for tag, _ in part if tag != "+")
        new_count = sum(1 for tag, _ in part if tag != "-")
        old_start = old_before[start] + 1
        new_start = new_before[start] + 1 if new_count else new_before[start]
        out.append(f"@@ -{old_start},{old_count} +{new_start},{new_count} @@")
        out.extend(tag + text for tag, text in part)
    return out
