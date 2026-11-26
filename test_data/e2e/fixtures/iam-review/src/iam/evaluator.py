"""Decision engine of the in-house IAM.

The evaluator is tooling, not configuration: it loads policy/policy.json and
policy/groups.json and answers allow or deny for one (principal, action, resource) request.

Rules:
  * `*` in a pattern matches any sequence of characters (including `:` and `/`), so
    `files:*` is every files action and `files/runbooks/*` every resource under that prefix;
  * a statement matches a request when its Principal, one of its Action patterns and one of
    its Resource patterns all match; a Principal is an exact name, `group:<name>` or `*`;
  * any matching Deny statement wins, then any matching Allow statement allows;
  * a request matched by no Allow statement is denied (default deny).

One request from the shell:
    python3 src/iam/evaluator.py <principal> <action> <resource> [--policy PATH]
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_POLICY_PATH = ROOT / "policy" / "policy.json"
DEFAULT_GROUPS_PATH = ROOT / "policy" / "groups.json"
EFFECTS = ("Allow", "Deny")


class PolicyError(ValueError):
    """The policy document, or one of its statements, is malformed."""


def _as_list(value, field, sid):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list) and value and all(isinstance(item, str) and item for item in value):
        return list(value)
    raise PolicyError("statement %s: %s must be a string or a non-empty list of strings" % (sid, field))


def normalise(policy):
    """Return the statements of a policy document as dicts with list valued fields."""
    statements = policy.get("Statement") if isinstance(policy, dict) else policy
    if not isinstance(statements, list) or not all(isinstance(item, dict) for item in statements):
        raise PolicyError("a policy is a document with a Statement list of objects")
    result = []
    for index, statement in enumerate(statements, 1):
        sid = statement.get("Sid", "#%d" % index)
        effect = statement.get("Effect")
        if effect not in EFFECTS:
            raise PolicyError("statement %s: Effect must be Allow or Deny" % sid)
        result.append({
            "Sid": str(sid),
            "Effect": effect,
            "Principal": _as_list(statement.get("Principal"), "Principal", sid),
            "Action": _as_list(statement.get("Action"), "Action", sid),
            "Resource": _as_list(statement.get("Resource"), "Resource", sid),
        })
    return result


def load_policy(path=None):
    path = Path(path) if path else DEFAULT_POLICY_PATH
    try:
        with open(str(path), "r", encoding="utf-8") as fh:
            return json.load(fh)
    except OSError as exc:
        raise PolicyError("cannot read the policy %s: %s" % (path, exc))
    except ValueError as exc:
        raise PolicyError("the policy %s is not valid JSON: %s" % (path, exc))


def load_groups(path=None):
    path = Path(path) if path else DEFAULT_GROUPS_PATH
    try:
        with open(str(path), "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _pattern_regex(pattern):
    return re.compile("^" + ".*".join(re.escape(part) for part in pattern.split("*")) + "$")


def action_matches(pattern, action):
    """True when an Action pattern covers the action (case sensitive, `*` is a wildcard)."""
    return _pattern_regex(pattern).match(action) is not None


def resource_matches(pattern, resource):
    """True when a Resource pattern covers the resource (case sensitive, `*` is a wildcard)."""
    return _pattern_regex(pattern).match(resource) is not None


def principal_matches(pattern, principal, groups=None):
    if pattern == "*":
        return True
    if pattern.startswith("group:"):
        members = (groups or {}).get(pattern[len("group:"):], [])
        return principal in members
    return pattern == principal


def statement_matches(statement, principal, action, resource, groups=None):
    groups = load_groups() if groups is None else groups
    return (any(principal_matches(item, principal, groups) for item in statement["Principal"])
            and any(action_matches(item, action) for item in statement["Action"])
            and any(resource_matches(item, resource) for item in statement["Resource"]))


def explain(policy, principal, action, resource, groups=None):
    """The (Effect, Sid) pairs of the statements that match the request."""
    groups = load_groups() if groups is None else groups
    matched = []
    for statement in normalise(policy):
        if statement_matches(statement, principal, action, resource, groups):
            matched.append((statement["Effect"], statement["Sid"]))
    return matched


def decide(policy, principal, action, resource, groups=None):
    """True when the policy allows the request. `policy` is a document or a path."""
    if not isinstance(policy, (dict, list)):
        policy = load_policy(policy)
    matched = explain(policy, principal, action, resource, groups)
    if any(effect == "Deny" for effect, _sid in matched):
        return False
    return any(effect == "Allow" for effect, _sid in matched)


def main(argv):
    args = [item for item in argv[1:] if not item.startswith("--")]
    if len(args) != 3 or "--help" in argv:
        print("usage: python3 src/iam/evaluator.py <principal> <action> <resource> [--policy PATH]")
        return 2
    policy_path = None
    if "--policy" in argv:
        position = argv.index("--policy")
        if position + 1 >= len(argv):
            print("--policy needs a path")
            return 2
        policy_path = argv[position + 1]
    try:
        matched = explain(load_policy(policy_path), args[0], args[1], args[2])
    except PolicyError as exc:
        print("policy error: %s" % exc)
        return 2
    print("allow" if any(effect == "Allow" for effect, _sid in matched) and
          not any(effect == "Deny" for effect, _sid in matched) else "deny")
    for effect, sid in matched:
        print("  %s %s" % (effect, sid))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
