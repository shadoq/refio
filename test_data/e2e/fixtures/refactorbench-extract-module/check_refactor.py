import ast
import importlib
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

MOVED = ["parse_iso_date", "days_between", "is_weekend", "business_days_between"]
STAYING = ["format_invoice_line", "overdue_invoices", "late_fee", "summary"]


def defined_functions(path):
    with open(path, encoding="utf-8") as handle:
        tree = ast.parse(handle.read())
    return {node.name for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}


def structure_problems():
    problems = []
    new_path = os.path.join(HERE, "datecalc.py")
    if not os.path.isfile(new_path):
        return ["datecalc.py does not exist"]
    in_new = defined_functions(new_path)
    in_old = defined_functions(os.path.join(HERE, "report.py"))
    for name in MOVED:
        if name not in in_new:
            problems.append("datecalc.py does not define %s" % name)
        if name in in_old:
            problems.append("report.py still defines %s" % name)
    for name in STAYING:
        if name not in in_old:
            problems.append("report.py lost %s" % name)
    if problems:
        return problems
    report = importlib.import_module("report")
    datecalc = importlib.import_module("datecalc")
    for name in MOVED:
        if getattr(report, name, None) is not getattr(datecalc, name):
            problems.append("report.%s is not the function from datecalc" % name)
    return problems


def main():
    suite = unittest.defaultTestLoader.loadTestsFromName("test_report")
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    problems = structure_problems()
    for problem in problems:
        print("STRUCTURE:", problem)
    if not result.wasSuccessful() or problems:
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
