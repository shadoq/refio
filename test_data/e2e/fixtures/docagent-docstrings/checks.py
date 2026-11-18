import ast
import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "inventory_utils.py")

# sha256 of ast.dump() of the module as shipped, docstrings stripped
ORIGINAL_AST_SHA256 = "4ab6ab9444f18abb57069d0a4e81ace9444f23bb750382e8443b68e2d33238f6"

# every pattern must match the function's docstring (case-insensitive)
REQUIRED = {
    "normalize_sku": [r"upper", r"hyphen|dash"],
    "validate_quantity": [r"clamp|clip|\bcap\b|capped|bound|limit|restrict|saturat|range", r"99"],
    "chunked": [r"chunk|piece|batch|group|slice|consecutive"],
    "parse_duration": [r"second"],
    "moving_average": [r"average|mean", r"window"],
    "merge_defaults": [r"default", r"none|null|unset|missing|skip|ignor"],
}


def strip_docstrings(tree):
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            body = node.body
            if body and isinstance(body[0], ast.Expr) and isinstance(getattr(body[0], "value", None), ast.Constant) \
                    and isinstance(body[0].value.value, str):
                node.body = body[1:] or [ast.Pass()]
    return tree


def main():
    problems = []
    with open(TARGET, encoding="utf-8") as handle:
        source = handle.read()
    try:
        tree = ast.parse(source)
    except SyntaxError as err:
        print("inventory_utils.py does not parse:", err)
        return 1

    digest = hashlib.sha256(ast.dump(strip_docstrings(ast.parse(source))).encode()).hexdigest()
    if digest != ORIGINAL_AST_SHA256:
        problems.append("code changed: apart from docstrings the module must stay identical")

    functions = {n.name: n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)}
    for name, patterns in REQUIRED.items():
        node = functions.get(name)
        if node is None:
            problems.append("function %s is missing" % name)
            continue
        doc = ast.get_docstring(node)
        if not doc or len(doc.strip()) < 20:
            problems.append("%s needs a real docstring" % name)
            continue
        for pattern in patterns:
            if not re.search(pattern, doc, re.IGNORECASE):
                problems.append("docstring of %s does not describe its behaviour (expected /%s/)" % (name, pattern))
    for name, node in functions.items():
        if not ast.get_docstring(node):
            problems.append("function %s has no docstring" % name)

    if problems:
        print("\n".join(problems))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
