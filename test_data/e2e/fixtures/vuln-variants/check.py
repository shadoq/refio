#!/usr/bin/env python3
"""Acceptance gate for the SEC-ADV-2026-031 variant analysis.

Three things are checked, all re-derived here from the seed data and from the report:

  a. report/variants.md - every `variant = path | function` line is reduced to a canonical
     spelling and looked up in a set of SHA-256 digests. The sibling list itself is not in
     this file, and the gate does not say which line is right or wrong.
  b. behaviour - every query function of the package that takes caller input is called
     against the seeded database with legitimate values and with injection payloads. The
     expected rows come from the gate's own parameterised statements over the same seed
     data, never from a stored list of rows. A payload must behave like the literal string
     it is: no rows, no error, no change to the database. Names with apostrophes and other
     awkward-but-honest input must keep matching.
  c. the database content is the same after all probes as on a fresh connection, and the
     function that was fixed in 0.4.2 still behaves.

Exit 0 only when every check passes. This file is part of the scenario definition - editing
it is not a fix.

Run: python3 check.py
"""
import hashlib
import importlib
import os
import re
import sys
import traceback

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
os.chdir(BASE)

from shop import db  # noqa: E402

REPORT = "report/variants.md"

# --- digest block begin ---
# One SHA-256 per accepted sibling, taken over "<path>|<function>" with the path
# relative to the project root. Managed by the security team's manifest tool; do not
# edit by hand.
SIBLING_DIGESTS = {
    "0f4c4bf106bd3ac668553905f0c5e94d19cd6411ece8b314b5ca189b6380c313",
    "307cab7b9ce0c1d3ce49ba923369a0a2aa25de0313f5c679bd612b7304278c94",
    "35216ce62b02843eaf49cd4118038ab3bb8d5b9dd88137ad8e295f8a25ff1b01",
    "4a09bdb3894a425d0860c6936288e63c9fae0889f6672df625a0967f02d61895",
    "723505abb1d8007007b1ddbe88438e10e451b1e19d42fe50069abba4d82b3097",
    "7772c7dbede36eb665aae60e627e7fd7bb7f47b757f4ef59631d390fb57326d2",
    "c091360245ee220c017ad365694f4d99bb1d1fb02b0559b0d76e520f74a52b90",
}
# --- digest block end ---


LINE_RE = re.compile(
    r"^\s*variant\s*=\s*(.+?)\s*\|\s*([A-Za-z_][A-Za-z0-9_]*(?:\(\))?)\s*$"
)

# Injection payloads in the style of the advisory's proof of concept plus the usual classes:
# tautology, boolean-based, UNION and stacked statements. Each one must come back with zero
# rows, like any other string that matches nothing.
PAYLOADS = [
    "' OR '1'='1",
    "' OR 1=1 --",
    "' AND '1'='2",
    "') OR ('1'='1",
    "' UNION SELECT 1, 2, 3, 4, 5 --",
    "x'; DROP TABLE customers; --",
    "x'; DELETE FROM orders; --",
    "' OR email LIKE '",
]

failures = []


def check(name, ok, detail=""):
    print(("PASS" if ok else "FAIL"), "-", name, ("- " + detail if not ok and detail else ""))
    if not ok:
        failures.append(name)


def fail(name, detail=""):
    check(name, False, detail)


# --- report -------------------------------------------------------------------------
def canonical(path, function):
    """Canonical '<path>|<function>' for one report entry, or None when it is not a project path."""
    path = path.strip().strip("`").strip()
    function = function.strip().strip("`").strip()
    if function.endswith("()"):
        function = function[:-2]
    if os.path.isabs(path):
        try:
            path = os.path.relpath(path, BASE)
        except ValueError:
            return None
    path = os.path.normpath(path).replace(os.sep, "/")
    if path.startswith("../"):
        return None
    return f"{path}|{function}"


def read_variant_lines():
    """Return (entries, format_errors): canonical spellings of the lines that parse, and the
    line numbers of `variant` lines that do not follow the form."""
    entries, errors = [], []
    with open(REPORT, encoding="utf-8") as fh:
        text = fh.read()
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        line = re.sub(r"^[-*]\s+", "", line)          # list bullet
        if line.startswith("|"):                      # table row
            line = line[1:]
            line = line[:-1] if line.endswith("|") else line
        if not re.match(r"^`?variant\b", line):
            continue
        match = LINE_RE.match(line)
        if not match:
            errors.append(number)
            continue
        spelled = canonical(match.group(1), match.group(2))
        if spelled is None:
            errors.append(number)
        else:
            entries.append(spelled)
    return entries, errors


def check_report():
    if not os.path.isfile(REPORT):
        fail("report/variants.md exists", "file not found")
        return
    entries, errors = read_variant_lines()
    if errors:
        fail("report lines are in the form `variant = path | function`",
             "malformed line(s): " + ", ".join(str(n) for n in errors))
    if not entries:
        fail("report lists at least one variant", "no variant line found")
        return
    listed = {hashlib.sha256(e.encode("utf-8")).hexdigest() for e in entries}
    extra = len(listed - SIBLING_DIGESTS)
    missing = len(SIBLING_DIGESTS - listed)
    check("report names exactly the unfixed siblings of SEC-ADV-2026-031",
          extra == 0 and missing == 0,
          f"{extra} listed function(s) are not a sibling (or path/function misspelled), "
          f"{missing} sibling(s) missing - the gate does not say which")


# --- behaviour ----------------------------------------------------------------------
def column(conn, sql):
    return [row[0] for row in conn.execute(sql)]


def words(conn):
    found = []
    for text in column(conn, "SELECT name FROM customers") + column(conn, "SELECT title FROM products"):
        for word in text.split():
            if word not in found:
                found.append(word)
    return found


def legitimate_values(conn, kind):
    """Honest caller input for one argument kind, taken from the seed rows."""
    if kind == "email":
        return column(conn, "SELECT email FROM customers ORDER BY id") + [
            "", "unknown@example.com", "DANA.PALMER@EXAMPLE.COM"]
    if kind == "code":
        return column(conn, "SELECT code FROM offers ORDER BY id") + ["", "NO-SUCH-CODE"]
    if kind == "region":
        return column(conn, "SELECT DISTINCT region FROM customers ORDER BY region") + [
            "", "Ulster", "LEINSTER"]
    if kind == "category":
        return column(conn, "SELECT DISTINCT category FROM products ORDER BY category") + [
            "", "Glass"]
    if kind == "term":
        return words(conn) + ["zzz", "select", "union", "or", "drop", "e"]
    if kind == "sku":
        return column(conn, "SELECT sku FROM products ORDER BY id") + ["", "ART-9999"]
    if kind == "ids":
        return [[1], [2, 5], [1, 2, 3, 4, 5], [999], []]
    if kind == "int":
        return sorted(set(column(conn, "SELECT stock FROM products"))) + [0, 5, 1000]
    if kind == "ts":
        return column(conn, "SELECT placed_at FROM orders ORDER BY id") + [
            "2026-02-01T00:00:00", "2026-03-01T00:00:00"]
    if kind == "status_group":
        return ["open", "closed"]
    raise ValueError(kind)


def rows(statement):
    """Reference for a function that returns a list of tuples: the gate's own statement."""
    def run(conn, args):
        return sorted(tuple(row) for row in conn.execute(statement, list(args)).fetchall())
    return run


def one(statement):
    """Reference for a function that returns one tuple or None."""
    def run(conn, args):
        got = conn.execute(statement, list(args)).fetchone()
        return None if got is None else tuple(got)
    return run


def scalar(statement):
    def run(conn, args):
        return conn.execute(statement, list(args)).fetchone()[0]
    return run


def id_rows(conn, args):
    ids = args[0]
    if not ids:
        return []
    marks = ", ".join("?" for _ in ids)
    return sorted(tuple(row) for row in conn.execute(
        f"SELECT id, email, name, region, tier FROM customers WHERE id IN ({marks}) ORDER BY id",
        list(ids)).fetchall())


GROUP_STATEMENTS = {
    "open": "SELECT id, status, total FROM orders WHERE status = 'pending' ORDER BY id",
    "closed": "SELECT id, status, total FROM orders "
              "WHERE status IN ('shipped', 'cancelled') ORDER BY id",
}


def status_rows(conn, args):
    return sorted(tuple(row) for row in conn.execute(GROUP_STATEMENTS[args[0]]).fetchall())


# Every query function of the package is probed, sibling or not, so this table says nothing
# about which ones are siblings. (module, function, reference, argument kinds, argument kinds
# that take a payload, result shape)
PROBES = [
    ("offers", "find_offer",
     one("SELECT id, code, title, region, discount, active FROM offers WHERE code = ?"),
     ["code"], ["code"], "one"),
    ("offers", "active_offers",
     rows("SELECT id, code, title, region, discount FROM offers WHERE active = 1 "
          "ORDER BY discount DESC, code"),
     [], [], "list"),
    ("orders", "orders_for_customer",
     rows("SELECT o.id, o.status, o.placed_at, o.total FROM orders o "
          "JOIN customers c ON c.id = o.customer_id WHERE c.email = ? ORDER BY o.placed_at, o.id"),
     ["email"], ["email"], "list"),
    ("orders", "orders_between",
     rows("SELECT id, customer_id, status, total FROM orders WHERE placed_at >= ? AND placed_at < ? "
          "ORDER BY id"), ["ts", "ts"], ["ts"], "list"),
    ("orders", "orders_in_status", status_rows, ["status_group"], [], "list"),
    ("orders", "order_count", scalar("SELECT COUNT(*) FROM orders"), [], [], "scalar"),
    ("customers", "customer_by_email",
     one("SELECT id, email, name, region, tier FROM customers WHERE email = ?"),
     ["email"], ["email"], "one"),
    ("customers", "customers_by_ids", id_rows, ["ids"], ["ids"], "list"),
    ("customers", "search_customers",
     rows("SELECT id, email, name, region FROM customers WHERE name LIKE '%' || ? || '%' "
          "ORDER BY name"), ["term"], ["term"], "list"),
    ("customers", "customers_in_region",
     rows("SELECT id, email, name, tier FROM customers WHERE region = ? ORDER BY name"),
     ["region"], ["region"], "list"),
    ("inventory", "product_by_sku",
     one("SELECT id, sku, title, category, price, stock FROM products WHERE sku = ?"),
     ["sku"], ["sku"], "one"),
    ("inventory", "search_titles",
     rows("SELECT sku, title FROM products WHERE title LIKE '%' || ? || '%' ORDER BY sku"),
     ["term"], ["term"], "list"),
    ("inventory", "products_in_category",
     rows("SELECT sku, title, price, stock FROM products WHERE category = ? ORDER BY price DESC, sku"),
     ["category"], ["category"], "list"),
    ("inventory", "low_stock",
     rows("SELECT sku, title, stock FROM products WHERE stock < ? ORDER BY stock, sku"),
     ["int"], [], "list"),
    ("reviews", "reviews_for_product",
     rows("SELECT r.id, c.email, r.rating, r.body, r.created_at FROM reviews r "
          "JOIN customers c ON c.id = r.customer_id JOIN products p ON p.id = r.product_id "
          "WHERE p.sku = ? ORDER BY r.id"), ["sku"], ["sku"], "list"),
    ("reviews", "reviews_by_customer",
     rows("SELECT r.id, p.sku, r.rating, r.body, r.created_at FROM reviews r "
          "JOIN products p ON p.id = r.product_id JOIN customers c ON c.id = r.customer_id "
          "WHERE c.email = ? ORDER BY r.created_at, r.id"), ["email"], ["email"], "list"),
    ("reports", "revenue_by_category",
     rows("SELECT p.category, ROUND(SUM(oi.qty * oi.unit_price), 2) FROM order_items oi "
          "JOIN products p ON p.id = oi.product_id GROUP BY p.category ORDER BY p.category"),
     [], [], "list"),
    ("reports", "top_customers_by_region",
     rows("SELECT c.name, COUNT(*) AS orders, ROUND(SUM(o.total), 2) AS spend FROM customers c "
          "JOIN orders o ON o.customer_id = c.id WHERE c.region = ? "
          "GROUP BY c.id ORDER BY orders DESC, c.name LIMIT 3"), ["region"], ["region"], "list"),
]


def normalise(result, shape):
    """Make a result comparable with the reference: lists as sorted tuples, one-row results as
    a tuple or None, scalars as they are."""
    if result is None:
        return None
    if shape == "scalar":
        return result
    if shape == "one":
        return tuple(result) if isinstance(result, (tuple, list)) else result
    try:
        return sorted(tuple(tuple(item) for item in result))
    except TypeError as exc:
        raise ValueError(f"rows are not tuples: {exc}")


def legit_cases(conn, kinds):
    """Argument tuples built from honest values: every value alone, and paired values for
    two-argument functions (start after end as well as the other way round)."""
    columns = [legitimate_values(conn, kind) for kind in kinds]
    if not columns:
        return [()]
    if len(columns) == 1:
        return [(value,) for value in columns[0]]
    first, second = columns
    if kinds[0] == kinds[1]:
        n = len(first)
        forward = [(first[i], first[(i + 3) % n]) for i in range(n)]
        return forward + [(b, a) for a, b in forward]
    return list(zip(first, second)) + list(zip(second, first))


def payload_cases(conn, kinds, attackable):
    base = legit_cases(conn, kinds)[0]
    cases = []
    for position, kind in enumerate(kinds):
        if kind not in attackable:
            continue
        for payload in PAYLOADS:
            args = list(base)
            args[position] = [payload] if kind == "ids" else payload
            cases.append((position, payload, tuple(args)))
    return cases


def call(function, conn, args):
    try:
        return function(conn, *args), None
    except Exception:  # any exception counts against the probe
        return None, traceback.format_exc(limit=1).strip().splitlines()[-1]


def fingerprint(conn):
    tables = column(conn, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    return {t: sorted(map(repr, conn.execute(f'SELECT * FROM "{t}"').fetchall())) for t in tables}


def show(label, problems):
    check(f"{label}: behave as parameterised queries for honest input and for payloads",
          not problems, "; ".join(problems[:3]) + (" ..." if len(problems) > 3 else ""))


def check_behaviour():
    conn = db.connect()
    functions = {}
    for probe in PROBES:
        module, name = probe[0], probe[1]
        try:
            functions[(module, name)] = getattr(importlib.import_module("shop." + module), name)
        except Exception as exc:  # a module or function of the package was removed or broken
            fail(f"shop.{module}.{name} is importable", f"{type(exc).__name__}: {exc}")
    for module, name, reference, kinds, attackable, shape in PROBES:
        function = functions.get((module, name))
        if function is None:
            continue
        problems = []
        cases = legit_cases(conn, kinds)
        for args in cases:
            got, error = call(function, conn, args)
            want = normalise(reference(conn, args), shape)
            try:
                got = normalise(got, shape)
            except ValueError as exc:
                error = str(exc)
            if error:
                problems.append(f"legitimate input {args!r}: raised {error}")
            elif got != want:
                problems.append(f"legitimate input {args!r}: returned {got!r}, expected {want!r}")
        attacks = payload_cases(conn, kinds, attackable)
        for position, payload, args in attacks:
            # The payload must behave like the literal string it is: whatever the gate's own
            # statement returns for it (nothing, except in an open-ended range comparison).
            want = normalise(reference(conn, args), shape)
            got, error = call(function, conn, args)
            if error:
                problems.append(f"payload {payload!r} in argument {position + 1}: raised {error}")
            elif got != want:
                got_count = "no rows" if got in (None, []) else f"{len(got)} row(s)"
                want_count = "none" if want in (None, []) else f"{len(want)} row(s) of data"
                problems.append(f"payload {payload!r} in argument {position + 1}: returned "
                                f"{got_count}, expected {want_count}")
        show(f"shop.{module}.{name} ({len(cases)} legitimate, {len(attacks)} payload calls)",
             problems)

    check("database content is unchanged after the probes",
          fingerprint(conn) == fingerprint(db.connect()),
          "a probe altered the data or the schema")


def main():
    check_report()
    check_behaviour()
    print()
    if failures:
        print(f"check.py: {len(failures)} check(s) FAILED")
        return 1
    print("check.py: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
