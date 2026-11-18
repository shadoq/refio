"""Runs the queries from queries.sql and compares their results with the reference results.

Every query is run against the rental database and against a second database that has
extra dirty rows of the same kinds, so a query that only handles part of the quirks
(or only returns the values of the first database) fails.
"""
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from build_db import build

REFERENCE = {
    "q1_completed_per_station": """
        SELECT TRIM(b.station) AS station, COUNT(*) AS n
        FROM (SELECT DISTINCT id, bike_id, status FROM rentals) r
        JOIN bikes b ON b.id = r.bike_id
        WHERE LOWER(TRIM(r.status)) = 'completed'
        GROUP BY TRIM(b.station)
        ORDER BY n DESC, station""",
    "q2_average_minutes_per_model": """
        SELECT b.model, ROUND(AVG(r.minutes), 1)
        FROM (SELECT DISTINCT id, bike_id, status, minutes FROM rentals) r
        JOIN bikes b ON b.id = r.bike_id
        WHERE LOWER(TRIM(r.status)) = 'completed' AND r.minutes IS NOT NULL
        GROUP BY b.model HAVING COUNT(*) >= 2
        ORDER BY b.model""",
    "q3_members_joined_2023": """
        SELECT DISTINCT LOWER(TRIM(full_name)) AS name FROM members
        WHERE (CASE WHEN instr(joined, '/') > 0 THEN substr(joined, 7, 4) ELSE substr(joined, 1, 4) END) = '2023'
        ORDER BY name""",
    "q4_fees_per_month": """
        SELECT CASE WHEN instr(start_date, '/') > 0
                    THEN substr(start_date, 7, 4) || '-' || substr(start_date, 4, 2)
                    ELSE substr(start_date, 1, 7) END AS month,
               SUM(COALESCE(fee_cents, 0))
        FROM (SELECT DISTINCT id, start_date, status, fee_cents FROM rentals) r
        WHERE LOWER(TRIM(status)) = 'completed'
        GROUP BY month ORDER BY month""",
    "q5_most_active_member": """
        SELECT LOWER(TRIM(m.full_name)) AS name, COUNT(*) AS n
        FROM (SELECT DISTINCT id, member_id, status FROM rentals) r
        JOIN (SELECT DISTINCT id, full_name FROM members) m ON m.id = r.member_id
        WHERE LOWER(TRIM(r.status)) = 'completed'
        GROUP BY m.id ORDER BY n DESC, name LIMIT 1""",
}

EXTRA = """
INSERT INTO members VALUES (200, '  ZOE PARK ', '17/03/2023', 'Oslo');
INSERT INTO members VALUES (200, '  ZOE PARK ', '17/03/2023', 'Oslo');
INSERT INTO members VALUES (201, 'amir  vega', '2023-12-31', 'Bergen');
INSERT INTO members VALUES (202, ' Nell Quade', '02/01/2024', 'Oslo');
INSERT INTO bikes VALUES (200, 'Trail', 'Harbour  ');
INSERT INTO bikes VALUES (201, 'Tandem', 'Harbour');
INSERT INTO bikes VALUES (202, 'Tandem', 'West ');
INSERT INTO rentals VALUES (900, 200, 200, '05/11/2023', 40, 300, ' COMPLETED');
INSERT INTO rentals VALUES (900, 200, 200, '05/11/2023', 40, 300, ' COMPLETED');
INSERT INTO rentals VALUES (901, 200, 201, '2023-11-20', NULL, NULL, 'completed ');
INSERT INTO rentals VALUES (902, 200, 201, '2023-11-21', 61, NULL, 'Completed');
INSERT INTO rentals VALUES (903, 201, 202, '30/12/2023', 25, 100, 'cancelled');
INSERT INTO rentals VALUES (904, 201, 202, '2024-01-04', 35, 220, 'completed');
INSERT INTO rentals VALUES (905, 202, 201, '12/01/2024', 10, 50, 'COMPLETED');
INSERT INTO rentals VALUES (906, 200, 202, '01/02/2024', 15, 80, 'active');
"""


def parse_queries(text):
    queries = {}
    name = None
    for line in text.splitlines():
        m = re.match(r"--\s*name:\s*(\S+)", line)
        if m:
            name = m.group(1)
            queries[name] = []
        elif name and not line.lstrip().startswith("--"):
            queries[name].append(line)
    cleaned = {}
    for key, lines in queries.items():
        sql = "\n".join(lines).strip()
        cleaned[key] = sql[:-1].strip() if sql.endswith(";") else sql
    return cleaned


def run(conn, sql):
    rows = []
    for r in conn.execute(sql).fetchall():
        rows.append(tuple(round(v, 6) if isinstance(v, float) else v for v in r))
    return rows


def main():
    with open(os.path.join(HERE, "queries.sql"), encoding="utf-8") as f:
        queries = parse_queries(f.read())
    failures = 0
    for name, reference in REFERENCE.items():
        sql = queries.get(name, "")
        if not sql:
            print("FAIL %s: no query written" % name)
            failures += 1
            continue
        if not re.match(r"(?is)\s*(select|with)\b", sql):
            print("FAIL %s: only SELECT statements are allowed" % name)
            failures += 1
            continue
        ok = True
        for label, extra in (("base data", False), ("extended data", True)):
            conn = build()
            if extra:
                conn.executescript(EXTRA)
            conn.execute("PRAGMA query_only = ON")
            try:
                got = run(conn, sql)
            except sqlite3.Error as exc:
                print("FAIL %s: %s on %s" % (name, exc, label))
                ok = False
                break
            want = run(conn, reference)
            if got != want:
                print("FAIL %s: result differs on %s (got %d rows, want %d rows)" % (name, label, len(got), len(want)))
                ok = False
                break
            conn.close()
        if ok:
            print("PASS %s" % name)
        else:
            failures += 1
    print("%d of %d queries correct" % (len(REFERENCE) - failures, len(REFERENCE)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
