#!/usr/bin/env python3
"""Offline gate for the stale-vhost cleanup.

Checks, without any network:
  1. the routing table no longer maps the stale host (old-app.example.test),
  2. the main host (app.example.test) is still routed to the main app,
  3. the server code no longer serves the takeover page for it (the route is gone),
  4. the report exists, contains the marker flag string and the two keys.

Exit code 0 means every check passed. This gate never prints accepted answers.
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

STALE_HOST = "old-app.example.test"
MAIN_HOST = "app.example.test"
FLAG_MARKER = bytes(b"FLAG{" + b"stale-vhost-takeover" + b"}")


def main():
    failures = []

    # 1. the routing table no longer maps the stale host.
    with open(os.path.join(HERE, "server/config/vhosts.json"), encoding="utf-8") as fh:
        table = json.load(fh)
    vhosts = table.get("vhosts", {})
    if STALE_HOST in vhosts:
        failures.append("routing table still maps the stale host")
    if any(v.get("app") == "legacy-takeover" for v in vhosts.values()):
        failures.append("routing table still references the legacy takeover app")

    # 2. the main host is intact.
    if vhosts.get(MAIN_HOST, {}).get("app") != "main":
        failures.append("main host is not routed to the main app anymore")

    # 3. the server code no longer serves the takeover page.
    with open(os.path.join(HERE, "server/app.py"), encoding="utf-8") as fh:
        app_src = fh.read()
    if "legacy-takeover" in app_src:
        failures.append("server code still contains the takeover route")
    if FLAG_MARKER.decode() in app_src:
        failures.append("server code still embeds the takeover marker page")

    # 4. the report exists, carries the flag marker and both keys.
    report_path = os.path.join(HERE, "report/takeover.md")
    if not os.path.isfile(report_path):
        failures.append("report/takeover.md is missing")
    else:
        with open(report_path, encoding="utf-8") as fh:
            report = fh.read()
        # the flag a live server hands out may be recorded verbatim
        if hashlib.sha256(FLAG_MARKER).hexdigest() not in (
                hashlib.sha256(report.encode()).hexdigest(),) and FLAG_MARKER.decode() not in report:
            failures.append("report does not contain the takeover marker")
        has_stale = any(line.strip().startswith("stale_host" + " ") or
                        line.strip().startswith("stale_host" + "=")
                        for line in report.splitlines())
        has_route = any(line.strip().startswith("affected_route" + " ") or
                        line.strip().startswith("affected_route" + "=")
                        for line in report.splitlines())
        if not has_stale:
            failures.append("report is missing the stale_host key")
        if not has_route:
            failures.append("report is missing the affected_route key")

    if failures:
        print("FAIL")
        for f in failures:
            print(" - " + f)
        return 1
    print("PASS: stale vhost unmapped, main host intact, takeover route removed, report complete")
    return 0


if __name__ == "__main__":
    sys.exit(main())
