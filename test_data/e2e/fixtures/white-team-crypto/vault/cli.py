"""Command line client for the document vault.

    VAULT_PASSWORD=... python3 -m vault add <id> <user> <title>     (body on stdin)
    VAULT_PASSWORD=... python3 -m vault get <id> <user>
    VAULT_PASSWORD=... python3 -m vault list <user>

Data files are resolved from the working directory; VAULT_DB and VAULT_USERS override them.
"""
import base64
import json
import os
import sys

from vault import legacy

VAULT_DB = "vault/vault.db"
USERS_DB = "vault/users.db"


def _paths():
    root = os.getcwd()
    return (
        os.environ.get("VAULT_DB", os.path.join(root, VAULT_DB)),
        os.environ.get("VAULT_USERS", os.path.join(root, USERS_DB)),
    )


def _load(path, default):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _save(path, data):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, sort_keys=True)
        fh.write("\n")


def _authenticate(user):
    """Return the caller's password once it matches the stored record, else exit."""
    password = os.environ.get("VAULT_PASSWORD", "")
    users = _load(_paths()[1], {})
    stored = users.get(user, {}).get("password_hash")
    if stored is None or not legacy.verify_password(stored, password):
        sys.exit("wrong user or password")
    return password


def cmd_add(args):
    record_id, user, title = args
    password = _authenticate(user)
    vault_path, _ = _paths()
    db = _load(vault_path, {"format": 1, "records": {}})
    if record_id in db["records"]:
        sys.exit("record id already exists: " + record_id)
    body = sys.stdin.read()
    db["records"][record_id] = {
        "owner": user,
        "title": title,
        "body": base64.b64encode(legacy.encrypt_record(password, body)).decode("ascii"),
    }
    _save(vault_path, db)


def cmd_get(args):
    record_id, user = args
    password = _authenticate(user)
    db = _load(_paths()[0], {"format": 1, "records": {}})
    record = db["records"].get(record_id)
    if record is None or record["owner"] != user:
        sys.exit("no such record")
    blob = base64.b64decode(record["body"])
    sys.stdout.write(legacy.decrypt_record(password, blob).decode("utf-8"))


def cmd_list(args):
    (user,) = args
    _authenticate(user)
    db = _load(_paths()[0], {"format": 1, "records": {}})
    for record_id, record in sorted(db["records"].items()):
        if record["owner"] == user:
            print(record_id, "-", record["title"])


COMMANDS = {"add": (cmd_add, 3), "get": (cmd_get, 2), "list": (cmd_list, 1)}


def main(argv):
    if not argv or argv[0] not in COMMANDS or len(argv) - 1 != COMMANDS[argv[0]][1]:
        sys.exit(__doc__.strip())
    COMMANDS[argv[0]][0](argv[1:])


if __name__ == "__main__":
    main(sys.argv[1:])
