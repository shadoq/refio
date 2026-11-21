import os
import random
import tempfile
import tracemalloc

import report

PEAK_LIMIT_BYTES = 3 * 1024 * 1024
LINES = 400_000


def generate(path):
    """Write a deterministic log and return the summary it must produce."""
    rng = random.Random(77)
    users = {}
    hourly = {}
    skipped = 0
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("ts,user,status,bytes\n")
        for i in range(LINES):
            if i % 997 == 0:
                out.write("not a log line\n")
                skipped += 1
                continue
            if i % 1499 == 0:
                out.write("2024-03-05T10:00:00,u0001,200,abc\n")
                skipped += 1
                continue
            hour = rng.randrange(24)
            user = "u%04d" % rng.randrange(300)
            status = rng.choice((200, 200, 200, 204, 301, 404, 500, 503))
            size = rng.randrange(100, 90000)
            out.write("2024-03-05T%02d:%02d:07,%s,%d,%d\n" % (hour, rng.randrange(60), user, status, size))
            entry = users.setdefault(user, {"requests": 0, "bytes": 0, "errors": 0})
            entry["requests"] += 1
            entry["bytes"] += size
            if status >= 500:
                entry["errors"] += 1
            key = "%02d" % hour
            hourly[key] = hourly.get(key, 0) + 1
    return {"users": users, "hourly": hourly, "skipped": skipped}


def check_small():
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "small.csv")
        with open(path, "w", encoding="utf-8", newline="\n") as out:
            out.write("ts,user,status,bytes\n")
            out.write("2024-01-01T09:15:00,ann,200,10\n")
            out.write("2024-01-01T09:45:00,ann,500,20\n")
            out.write("broken\n")
            out.write("2024-01-01T23:00:00,bob,404,5\n")
            out.write("2024-01-01T23:00:01,bob,x,5\n")
        got = report.summarize(path)
    want = {
        "users": {
            "ann": {"requests": 2, "bytes": 30, "errors": 1},
            "bob": {"requests": 1, "bytes": 5, "errors": 0},
        },
        "hourly": {"09": 2, "23": 1},
        "skipped": 2,
    }
    assert got == want, "small file: got %r" % (got,)


def check_large():
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "big.csv")
        want = generate(path)
        size_mb = os.path.getsize(path) / (1024 * 1024)
        tracemalloc.start()
        try:
            got = report.summarize(path)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
    assert got == want, "large file: summary differs from the expected one"
    assert peak < PEAK_LIMIT_BYTES, (
        "peak memory %.1f MiB on a %.1f MiB file, limit %.1f MiB"
        % (peak / 1048576, size_mb, PEAK_LIMIT_BYTES / 1048576)
    )
    print("ok: peak %.2f MiB on a %.1f MiB file" % (peak / 1048576, size_mb))


if __name__ == "__main__":
    check_small()
    check_large()
    print("all checks passed")
