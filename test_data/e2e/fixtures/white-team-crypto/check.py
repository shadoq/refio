#!/usr/bin/env python3
"""Acceptance gate for the vault rekeying (crypto remediation).

It imports the NEW crypto module and proves the properties by experiment: the attacks the
legacy scheme allowed are closed, the legitimate use (round trip, passwords, the CLI) still
works, the live data files are migrated and the report is complete. Exit 0 only when every
check passes. This file is part of the scenario definition - editing it is not a fix.

Run: python3 check.py
"""
import ast
import base64
import hashlib
import hmac
import importlib
import json
import os
import random
import re
import secrets
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)

REPORT = os.path.join(BASE, "report", "remediation.md")
LIVE_VAULT = os.path.join(BASE, "vault", "vault.db")
LIVE_USERS = os.path.join(BASE, "vault", "users.db")
OLD_VAULT = os.path.join(BASE, "vault", "vault.legacy.db")
OLD_USERS = os.path.join(BASE, "vault", "users.legacy.db")

VERSION = 0x02
NONCE_LEN = 16
TAG_LEN = 32
MIN_ROUNDS = 200000
PASSWORD_RECORD = re.compile(r"^pbkdf2_sha256\$(\d+)\$([0-9a-f]+)\$([0-9a-f]{64})$")

# The rollout passwords of the test vault (same values as migration/rollout.json).
PASSWORDS = {
    "alice": "sunshine42",
    "bob": "hunter2-bob",
    "carol": "Tr0ub4dor&3",
    "dana": "vault#2020",
}
WRONG_PASSWORD = "not-the-password"

# sha256("<cwe lower-case>|<name of the legacy function that carries the weakness>") for every
# accepted `legacy_weakness = ...` line of the report.
WEAKNESS_DIGESTS = {
    "01b53611d0832271126c2f2dfeb35848369546c0d2b623f2062a1ebec357fbfa",
    "c4ab2eefd3011e11b2808b7aba3d43ddbcc3c0e5457c3c1d27586783a20c357a",
    "399b7d864a4ef27b0674b2e3cc93aee781fb9fa975722bb228a7212e7b4426aa",
    "e5b5f1b7a38c3a32fa02a9079daaf25fcba3b8fc82b36e7a21129dfde907976a",
    "9917fa5856a9fdea817124c580a9718ed116e0c0bcac90284bb47ee042b55e60",
    "702447d54cb0eb8422f7ba464c1180713b5b2e1756659caddfe4d78d3d503118",
    "5e2836cf02db42dc6640ed9808cd77f13c6831ff16ed428b152cf38066bbbf0a",
    "35006f2799cbeed5c6e7cac5a7b28133062522292f03cbdd56dad1ed1b4fa0c5",
    "305261fe9ccae698508b8e9b7a26ddb2fbb84ce869ae3d18e3b3edf3af5c885b",
}
MIGRATION_NAME = re.compile(r"legacy|migrat|reencrypt", re.I)

failures = []


def check(name, ok, detail=""):
    print(("PASS" if ok else "FAIL"), "-", name)
    if not ok:
        print("      " + detail)
        failures.append(name)


def problem(exc):
    return "raised %s: %s" % (type(exc).__name__, exc)


# --- spies: installed before the new module is imported ------------------------
import vault.legacy as legacy  # noqa: E402

legacy_calls = []
md5_calls = []
pbkdf2_calls = []
entropy_calls = []
prng_calls = []
compare_digest_calls = []


def _spy_legacy(name):
    original = getattr(legacy, name)

    def wrapper(*args, **kwargs):
        legacy_calls.append(name)
        return original(*args, **kwargs)

    setattr(legacy, name, wrapper)


for _name in ("keystream", "weak_mac", "encrypt_record", "verify_mac", "decrypt_record",
              "hash_password", "verify_password"):
    _spy_legacy(_name)

_real_md5 = hashlib.md5
_real_pbkdf2 = hashlib.pbkdf2_hmac
_real_compare_digest = hmac.compare_digest
_real_urandom = os.urandom


def _md5_spy(*args, **kwargs):
    md5_calls.append(1)
    return _real_md5(*args, **kwargs)


def _pbkdf2_spy(hash_name, password, salt, iterations, dklen=None):
    pbkdf2_calls.append((str(hash_name).lower(), bytes(salt), int(iterations)))
    return _real_pbkdf2(hash_name, password, salt, iterations, dklen)


def _compare_digest_spy(a, b):
    compare_digest_calls.append(1)
    return _real_compare_digest(a, b)


def _urandom_spy(n):
    entropy_calls.append("os.urandom")
    return _real_urandom(n)


def _entropy_spy(name):
    original = getattr(secrets, name)

    def wrapper(*args, **kwargs):
        entropy_calls.append(name)
        return original(*args, **kwargs)

    return wrapper


class _CountingSystemRandom(secrets.SystemRandom):
    def _count(self, value):
        entropy_calls.append("SystemRandom")
        return value

    def random(self):
        return self._count(super().random())

    def getrandbits(self, k):
        return self._count(super().getrandbits(k))

    def randbytes(self, n):
        return self._count(super().randbytes(n))

    def choice(self, seq):
        return self._count(super().choice(seq))


def _prng_spy(name):
    original = getattr(random, name)

    def wrapper(*args, **kwargs):
        prng_calls.append(name)
        return original(*args, **kwargs)

    return wrapper


def install_spies():
    hashlib.md5 = _md5_spy
    hashlib.pbkdf2_hmac = _pbkdf2_spy
    hmac.compare_digest = _compare_digest_spy
    for name in ("token_bytes", "token_hex", "token_urlsafe", "choice", "randbelow", "randbits"):
        setattr(secrets, name, _entropy_spy(name))
    secrets.SystemRandom = _CountingSystemRandom
    for name in ("random", "randbytes", "getrandbits", "choice", "randint", "randrange", "uniform"):
        setattr(random, name, _prng_spy(name))
    os.urandom = _urandom_spy


def counters():
    return {"legacy": len(legacy_calls), "md5": len(md5_calls), "pbkdf2": len(pbkdf2_calls),
            "entropy": len(entropy_calls), "compare_digest": len(compare_digest_calls),
            "prng": len(prng_calls)}


def clean_operation(label, before, need_entropy=False, need_pbkdf2=True, need_compare_digest=False):
    """Fail when anything forbidden happened since `before` (new operations only)."""
    problems = []
    called = sorted(set(legacy_calls[before["legacy"]:]))
    if called:
        problems.append("legacy function(s) called outside the migration: " + ", ".join(called))
    if len(md5_calls) != before["md5"]:
        problems.append("MD5 is still used")
    if len(prng_calls) != before["prng"]:
        problems.append("predictable random module used: " + ", ".join(sorted(set(prng_calls))))
    if need_entropy and len(entropy_calls) == before["entropy"]:
        problems.append("no random bytes drawn from secrets or os.urandom")
    if need_compare_digest and len(compare_digest_calls) == before["compare_digest"]:
        problems.append("hmac.compare_digest was not called")
    derived = pbkdf2_calls[before["pbkdf2"]:]
    if need_pbkdf2 and not derived:
        problems.append("pbkdf2_hmac was not called")
    for hash_name, _salt, iterations in derived:
        if hash_name != "sha256":
            problems.append("PBKDF2 used with hash %r instead of sha256" % hash_name)
        if iterations < MIN_ROUNDS:
            problems.append("PBKDF2 used with only %d rounds (minimum %d)" % (iterations, MIN_ROUNDS))
    check(label, not problems, "; ".join(problems))


def load_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def password_record_problems(stored, password):
    """Return the problems of one stored password record ([] when it is sound)."""
    match = PASSWORD_RECORD.match(stored) if isinstance(stored, str) else None
    if not match:
        return ["stored record is not pbkdf2_sha256$<rounds>$<salt hex>$<digest hex>"]
    problems = []
    rounds = int(match.group(1))
    try:
        salt = bytes.fromhex(match.group(2))
    except ValueError:
        return ["salt is not valid hex"]
    if rounds < MIN_ROUNDS:
        problems.append("only %d rounds (minimum %d)" % (rounds, MIN_ROUNDS))
    if len(salt) < 8:
        problems.append("salt is shorter than 8 bytes")
    recomputed = _real_pbkdf2("sha256", password.encode("utf-8"), salt, rounds).hex()
    if not _real_compare_digest(recomputed, match.group(3)):
        problems.append("digest does not recompute with PBKDF2-HMAC-SHA256")
    return problems


def read_report():
    if not os.path.isfile(REPORT):
        check("report/remediation.md exists", False, "file not found")
        return None
    with open(REPORT, "r", encoding="utf-8") as fh:
        return fh.read().splitlines()


def key_values(lines, key):
    """Values of the `key = value` lines of a report (one per line, optional list bullet)."""
    pattern = re.compile(r"^\s*(?:[-*]\s+)?`?" + re.escape(key) + r"`?\s*=\s*(.*?)\s*$")
    return [match.group(1).strip("`") for match in map(pattern.match, lines) if match]



CRYPTO_SRC = os.path.join(BASE, "vault", "crypto.py")

# --- the legacy snapshot: the reference the migration is compared against -------
old_records, old_plaintexts, old_users = {}, {}, {}
try:
    old_vault = load_json(OLD_VAULT)
    old_users = load_json(OLD_USERS)
    old_records = old_vault["records"]
    for _record_id, _record in old_records.items():
        old_plaintexts[_record_id] = legacy.decrypt_record(
            PASSWORDS[_record["owner"]], base64.b64decode(_record["body"]))
    check("legacy snapshot (vault/vault.legacy.db, vault/users.legacy.db) is readable", True)
except Exception as exc:
    old_records, old_plaintexts, old_users = {}, {}, {}
    check("legacy snapshot (vault/vault.legacy.db, vault/users.legacy.db) is readable", False, problem(exc))

# --- 1. the new module and its API ---------------------------------------------
install_spies()
crypto = None
try:
    crypto = importlib.import_module("vault.crypto")
    check("vault/crypto.py imports cleanly", True)
except Exception as exc:
    check("vault/crypto.py imports cleanly", False, problem(exc))
    crypto = None

error_class = None
if crypto is not None:
    functions = ("encrypt_record", "decrypt_record", "hash_password", "verify_password",
                 "reencrypt_all", "derive_key")
    missing = [name for name in functions if not callable(getattr(crypto, name, None))]
    check("module exposes encrypt_record, decrypt_record, hash_password, verify_password, "
          "reencrypt_all and derive_key as functions",
          not missing, "missing or not callable: " + ", ".join(missing))
    candidate = getattr(crypto, "IntegrityError", None)
    if isinstance(candidate, type) and issubclass(candidate, BaseException):
        error_class = candidate
        check("module defines the IntegrityError exception class", True)
    else:
        check("module defines the IntegrityError exception class", False,
              "IntegrityError is missing or is not an exception class")
    if missing or error_class is None:
        crypto = None
    if crypto is not None and getattr(crypto, "compare_digest", None) is _real_compare_digest:
        crypto.compare_digest = _compare_digest_spy

PLAIN = bytes(range(64))
CT_START, CT_END = 1 + NONCE_LEN, -TAG_LEN


# --- 2. the attacks the legacy scheme allowed must be closed -------------------
def cipher_checks():
    blobs = []
    for label in ("first", "second"):
        try:
            before = counters()
            blobs.append(bytes(crypto.encrypt_record(PASSWORDS["alice"], PLAIN)))
            clean_operation("%s encrypt_record: entropy from secrets, PBKDF2-SHA256 with at "
                            "least %d rounds, no MD5 and no legacy code" % (label, MIN_ROUNDS),
                            before, need_entropy=True)
        except Exception as exc:
            check("%s encrypt_record works" % label, False, problem(exc))
    if len(blobs) != 2:
        return
    first, second = blobs
    check("record layout is 0x02 || nonce (16) || ciphertext (len of plaintext) || tag (32)",
          len(first) == 1 + NONCE_LEN + len(PLAIN) + TAG_LEN and first[0] == VERSION,
          "got %d bytes, version byte 0x%02x" % (len(first), first[0] if first else 0))
    check("nonce is fresh for every message", first[1:CT_START] != second[1:CT_START],
          "both records carry the same nonce")
    body_a, body_b = first[CT_START:CT_END], second[CT_START:CT_END]
    same = sum(x == y for x, y in zip(body_a, body_b))
    check("same plaintext twice does not reuse the keystream",
          first != second and same <= len(PLAIN) // 8,
          "%d of %d ciphertext bytes are identical (legacy: all of them)" % (same, len(body_a)))

    try:
        before = counters()
        crypto.encrypt_record(PASSWORDS["alice"], PLAIN)
        derived_a = pbkdf2_calls[before["pbkdf2"]:]
        before = counters()
        crypto.encrypt_record(PASSWORDS["bob"], PLAIN)
        derived_b = pbkdf2_calls[before["pbkdf2"]:]
        shared = {salt for _h, salt, _r in derived_a} & {salt for _h, salt, _r in derived_b}
        check("PBKDF2 salt is not one constant shared by every user",
              bool(derived_a) and bool(derived_b) and not shared,
              "the same salt is used for two different passwords")
    except Exception as exc:
        check("encrypt_record works for two different users", False, problem(exc))

    try:
        zero = bytes(crypto.encrypt_record(PASSWORDS["alice"], bytes(64)))
        stream = zero[CT_START:CT_END]
        blocks = {stream[i:i + 16] for i in range(0, 64, 16)}
        check("keystream does not repeat every 16 bytes "
              "(legacy: a constant plaintext gives four identical blocks)",
              len(stream) == 64 and len(blocks) == 4)
    except Exception as exc:
        check("keystream period experiment runs", False, problem(exc))

    samples = [b"", "short note", "x" * 1000, "Accents: e a u c n - currency: EUR 12.50",
               bytes(range(256)) * 3]
    for index, sample in enumerate(samples, 1):
        data = sample.encode("utf-8") if isinstance(sample, str) else sample
        try:
            blob = bytes(crypto.encrypt_record(PASSWORDS["carol"], data))
            before = counters()
            back = crypto.decrypt_record(PASSWORDS["carol"], blob)
            clean_operation("round trip %d: decrypt_record uses no MD5 and no legacy code" % index,
                            before, need_pbkdf2=False)
            back = bytes(back)
            check("round trip %d returns the original plaintext" % index, back == data,
                  "got %d of %d bytes back" % (len(back), len(data)))
        except Exception as exc:
            check("round trip %d works" % index, False, problem(exc))

    def must_reject(label, blob, password):
        try:
            result = crypto.decrypt_record(password, blob)
        except error_class:
            check(label, True)
            return
        except Exception as exc:
            check(label, False, "raised %s instead of crypto.IntegrityError: %s"
                  % (type(exc).__name__, exc))
            return
        check(label, False, "decrypt_record returned data instead of refusing: %r"
              % (bytes(result)[:40],))

    before = counters()
    try:
        good = bytes(crypto.encrypt_record(PASSWORDS["dana"], b"customer letter for the gate"))

        def flipped(position):
            bad = bytearray(good)
            bad[position] ^= 0x01
            return bytes(bad)

        must_reject("a flipped ciphertext byte (first) is refused with IntegrityError",
                    flipped(CT_START), PASSWORDS["dana"])
        must_reject("a flipped ciphertext byte (last) is refused with IntegrityError",
                    flipped(len(good) - TAG_LEN - 1), PASSWORDS["dana"])
        must_reject("a tampered tag is refused with IntegrityError",
                    flipped(len(good) - 1), PASSWORDS["dana"])
        must_reject("a swapped nonce is refused with IntegrityError", flipped(1), PASSWORDS["dana"])
        must_reject("a changed version byte is refused with IntegrityError",
                    flipped(0), PASSWORDS["dana"])
        must_reject("a truncated record is refused with IntegrityError",
                    good[:-1], PASSWORDS["dana"])
        must_reject("a record with appended bytes is refused with IntegrityError",
                    good + b"\x00", PASSWORDS["dana"])
        must_reject("an empty record is refused with IntegrityError", b"", PASSWORDS["dana"])
        must_reject("the wrong password is refused with IntegrityError",
                    good, WRONG_PASSWORD)
        must_reject("another user's password is refused with IntegrityError",
                    good, PASSWORDS["alice"])
        if old_records:
            sample_id = sorted(old_records)[0]
            must_reject("a legacy-format record is not accepted by the new decrypt_record",
                        base64.b64decode(old_records[sample_id]["body"]),
                        PASSWORDS[old_records[sample_id]["owner"]])
    except Exception as exc:
        check("tamper and wrong-password experiments run", False, problem(exc))
    clean_operation("refusing bad records uses no MD5 and no legacy code", before,
                    need_pbkdf2=False)


def password_checks():
    try:
        before = counters()
        first_record = crypto.hash_password(PASSWORDS["alice"])
        clean_operation("hash_password: fresh salt, PBKDF2-SHA256 with at least %d rounds, no MD5"
                        % MIN_ROUNDS, before, need_entropy=True)
        before = counters()
        second_record = crypto.hash_password(PASSWORDS["alice"])
        clean_operation("second hash_password is clean as well", before, need_entropy=True)
        check("hash_password returns different records for the same password twice",
              isinstance(first_record, str) and isinstance(second_record, str)
              and first_record != second_record, "got %r" % (first_record,))
        found = password_record_problems(first_record, PASSWORDS["alice"])
        check("password record has the form pbkdf2_sha256$<rounds>$<salt hex>$<digest hex> "
              "and its digest recomputes", not found, "; ".join(found))

        before = counters()
        accepted = crypto.verify_password(first_record, PASSWORDS["alice"])
        clean_operation("verify_password (right password) recomputes PBKDF2 and compares "
                        "with hmac.compare_digest", before, need_compare_digest=True)
        check("verify_password accepts the right password", accepted is True,
              "returned %r" % (accepted,))
        before = counters()
        refused = crypto.verify_password(first_record, WRONG_PASSWORD)
        clean_operation("verify_password (wrong password) recomputes PBKDF2 and compares "
                        "with hmac.compare_digest", before, need_compare_digest=True)
        check("verify_password rejects a wrong password", refused is False,
              "returned %r" % (refused,))
        bare_md5 = _real_md5(PASSWORDS["alice"].encode("utf-8")).hexdigest()
        check("a bare MD5 digest is not accepted as a stored password",
              crypto.verify_password(bare_md5, PASSWORDS["alice"]) is False)
        check("a malformed or too weak stored record is refused",
              crypto.verify_password("", PASSWORDS["alice"]) is False
              and crypto.verify_password("pbkdf2_sha256$1$00$00", PASSWORDS["alice"]) is False)
    except Exception as exc:
        check("password hashing experiments run", False, problem(exc))


# --- 3. the live data files must be migrated -----------------------------------
def vault_problems(vault_path):
    """Problems of a migrated vault file ([] when every record is sound)."""
    try:
        records = load_json(vault_path)["records"]
    except Exception as exc:
        return ["cannot read the vault file: " + problem(exc)]
    problems = []
    if set(records) != set(old_records):
        problems.append("record ids differ from the legacy snapshot")
    for record_id in sorted(old_records):
        record = records.get(record_id)
        if not isinstance(record, dict):
            problems.append(record_id + ": record is missing")
            continue
        owner = old_records[record_id]["owner"]
        if (record.get("owner") != owner
                or record.get("title") != old_records[record_id]["title"]):
            problems.append(record_id + ": owner or title changed")
        try:
            blob = base64.b64decode(record["body"], validate=True)
        except Exception:
            problems.append(record_id + ": body is not base64")
            continue
        if not blob or blob[0] != VERSION:
            problems.append("%s: version byte is 0x%02x, want 0x02"
                            % (record_id, blob[0] if blob else 0))
            continue
        expected = old_plaintexts.get(record_id)
        if expected is None or len(blob) != 1 + NONCE_LEN + len(expected) + TAG_LEN:
            problems.append(record_id + ": body has the wrong length for format 0x02")
        try:
            plaintext = bytes(crypto.decrypt_record(PASSWORDS[owner], blob))
        except Exception as exc:
            problems.append("%s: decrypt_record raised %s: %s"
                            % (record_id, type(exc).__name__, exc))
            continue
        if plaintext != expected:
            problems.append(record_id + ": plaintext differs from the legacy record")
    return problems


def user_problems(users_path):
    """Problems of a migrated user table ([] when every password record is sound)."""
    try:
        users = load_json(users_path)
    except Exception as exc:
        return ["cannot read the user table: " + problem(exc)]
    problems, salts = [], []
    for user in sorted(old_users):
        stored = users.get(user, {}).get("password_hash") if isinstance(users.get(user), dict) else None
        if stored == old_users[user].get("password_hash"):
            problems.append(user + ": still the unsalted MD5 digest")
            continue
        problems.extend("%s: %s" % (user, item)
                        for item in password_record_problems(stored, PASSWORDS[user]))
        try:
            if crypto.verify_password(stored, PASSWORDS[user]) is not True:
                problems.append(user + ": verify_password rejects the rollout password")
            if crypto.verify_password(stored, WRONG_PASSWORD) is not False:
                problems.append(user + ": verify_password accepts a wrong password")
        except Exception as exc:
            problems.append("%s: verify_password raised %s" % (user, type(exc).__name__))
        if isinstance(stored, str) and stored.count("$") == 3:
            salts.append(stored.split("$")[2])
    if len(set(salts)) != len(salts):
        problems.append("users share one password salt")
    return problems


def shared_keystream_check():
    """Two records of one owner must no longer cancel out each other's keystream."""
    owners = {}
    for record_id in sorted(old_records):
        owners.setdefault(old_records[record_id]["owner"], []).append(record_id)
    pair = next((ids[:2] for ids in owners.values() if len(ids) >= 2), None)
    if pair is None:
        return
    try:
        records = load_json(LIVE_VAULT)["records"]
        blobs = [base64.b64decode(records[record_id]["body"], validate=True) for record_id in pair]
        if any(len(blob) <= 1 + NONCE_LEN + TAG_LEN or blob[0] != VERSION for blob in blobs):
            return
        bodies = [blob[CT_START:CT_END] for blob in blobs]
        texts = [old_plaintexts[record_id] for record_id in pair]
        width = min(len(body) for body in bodies)
        leaked = sum((bodies[0][i] ^ bodies[1][i]) == (texts[0][i] ^ texts[1][i])
                     for i in range(width))
        check("two records of the same owner no longer share a keystream", leaked <= width // 8,
              "%d of %d bytes cancel out (legacy: all of them)" % (leaked, width))
    except Exception as exc:
        check("shared-keystream experiment runs", False, problem(exc))


def live_data_checks():
    before = counters()
    found = vault_problems(LIVE_VAULT)
    clean_operation("reading the live records uses no MD5 and no legacy code", before,
                    need_pbkdf2=False)
    check("vault.db: every record carries version 0x02 and decrypts with its owner's password "
          "to the legacy plaintext", not found, "; ".join(found[:6]))
    before = counters()
    found = user_problems(LIVE_USERS)
    clean_operation("checking the live password records uses no MD5 and no legacy code", before,
                    need_pbkdf2=False)
    check("users.db: every user has an own-salt PBKDF2 password record that verifies",
          not found, "; ".join(found[:6]))
    shared_keystream_check()


def migration_checks():
    workdir = tempfile.mkdtemp(prefix="vault-gate-")
    try:
        tmp_vault = os.path.join(workdir, "vault.db")
        tmp_users = os.path.join(workdir, "users.db")
        shutil.copyfile(OLD_VAULT, tmp_vault)
        shutil.copyfile(OLD_USERS, tmp_users)
        before = counters()
        count = crypto.reencrypt_all(tmp_vault, tmp_users, dict(PASSWORDS))
        wrote_legacy = "encrypt_record" in legacy_calls[before["legacy"]:]
        check("reencrypt_all returns the number of migrated records and never writes "
              "legacy-format records",
              count == len(old_records) and not wrote_legacy,
              "returned %r%s" % (count, "; the legacy encrypt function was called" if wrote_legacy else ""))
        found = vault_problems(tmp_vault)
        check("reencrypt_all output: every record is in format 0x02 and decrypts to the "
              "legacy plaintext", not found, "; ".join(found[:6]))
        found = user_problems(tmp_users)
        check("reencrypt_all output: every user gets a salted PBKDF2 password record",
              not found, "; ".join(found[:6]))
    except Exception as exc:
        check("reencrypt_all migrates a legacy vault in place", False, problem(exc))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# --- 4. legitimate use: the application keeps working --------------------------
def run_cli(args, password, vault_path, users_path, stdin=""):
    env = dict(os.environ, VAULT_PASSWORD=password, VAULT_DB=vault_path,
               VAULT_USERS=users_path, PYTHONDONTWRITEBYTECODE="1")
    return subprocess.run([sys.executable, "-B", "-m", "vault"] + args, cwd=BASE, input=stdin,
                          capture_output=True, text=True, timeout=30, env=env)


def cli_checks():
    workdir = tempfile.mkdtemp(prefix="vault-gate-cli-")
    try:
        tmp_vault = os.path.join(workdir, "vault.db")
        tmp_users = os.path.join(workdir, "users.db")
        shutil.copyfile(LIVE_VAULT, tmp_vault)
        shutil.copyfile(LIVE_USERS, tmp_users)
        sample_id = sorted(old_records)[0]
        owner = old_records[sample_id]["owner"]
        text = old_plaintexts[sample_id].decode("utf-8")
        paths = (tmp_vault, tmp_users)

        result = run_cli(["get", sample_id, owner], PASSWORDS[owner], *paths)
        check("CLI: `python3 -m vault get` reads a migrated record with the right password",
              result.returncode == 0 and result.stdout == text,
              "exit %d, stdout %r, stderr %r" % (result.returncode, result.stdout[:80], result.stderr[-200:]))
        result = run_cli(["get", sample_id, owner], WRONG_PASSWORD, *paths)
        check("CLI: a wrong password is refused",
              result.returncode != 0 and text not in result.stdout,
              "exit %d" % result.returncode)
        body = "written by the acceptance gate\n"
        added = run_cli(["add", "rec-gate-1", owner, "Gate check"], PASSWORDS[owner], *paths, stdin=body)
        read_back = run_cli(["get", "rec-gate-1", owner], PASSWORDS[owner], *paths)
        stored = load_json(tmp_vault)["records"].get("rec-gate-1")
        blob = base64.b64decode(stored["body"]) if isinstance(stored, dict) else b""
        check("CLI: `add` then `get` round-trips a new record and stores it in format 0x02",
              added.returncode == 0 and read_back.returncode == 0 and read_back.stdout == body
              and blob[:1] == b"\x02",
              "exit %d / %d, stored version byte 0x%02x" % (added.returncode, read_back.returncode, blob[0] if blob else 0))
        result = run_cli(["list", owner], PASSWORDS[owner], *paths)
        check("CLI: `python3 -m vault list` still works",
              result.returncode == 0 and sample_id in result.stdout,
              "exit %d, stdout %r" % (result.returncode, result.stdout[:80]))
    except Exception as exc:
        check("CLI experiments run", False, problem(exc))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# --- 5. structure of the new module --------------------------------------------
def source_checks():
    if not os.path.isfile(CRYPTO_SRC):
        check("vault/crypto.py exists", False, "file not found")
    else:
        with open(CRYPTO_SRC, "r", encoding="utf-8") as fh:
            tree = ast.parse(fh.read())
        parents = {}
        for node in ast.walk(tree):
            for child in ast.iter_child_nodes(node):
                parents[child] = node
        md5_names = {"md5"}
        uses_secrets, prng_imports, compare_sites = False, [], 0
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module == "hashlib":
                md5_names.update(alias.asname or alias.name for alias in node.names if alias.name == "md5")
            if isinstance(node, ast.Import) and any(alias.name == "secrets" for alias in node.names):
                uses_secrets = True
            if isinstance(node, ast.ImportFrom) and node.module == "secrets":
                uses_secrets = True
            if isinstance(node, ast.Import) and any(alias.name.split(".")[0] == "random" for alias in node.names):
                prng_imports.append("import random")
            if isinstance(node, ast.ImportFrom) and (node.module or "").split(".")[0] == "random":
                prng_imports.extend("from random import " + alias.name for alias in node.names if alias.name != "SystemRandom")
            if isinstance(node, ast.Call) and (
                    (isinstance(node.func, ast.Attribute) and node.func.attr == "compare_digest")
                    or (isinstance(node.func, ast.Name) and node.func.id == "compare_digest")):
                compare_sites += 1

        offenders = []
        for node in ast.walk(tree):
            used = ((isinstance(node, ast.Attribute) and node.attr in md5_names)
                    or (isinstance(node, ast.Name) and node.id in md5_names)
                    or (isinstance(node, ast.Constant) and node.value in ("md5", b"md5")))
            if not used:
                continue
            names = []
            walker = node
            while walker in parents:
                walker = parents[walker]
                if isinstance(walker, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    names.append(walker.name)
            if not any(MIGRATION_NAME.search(name) for name in names):
                offenders.append(".".join(reversed(names)) or "module level")
        check("MD5 is only referenced inside the migration path (a function whose name contains "
              "legacy, migrat or reencrypt)", not offenders, "MD5 used in: " + ", ".join(sorted(set(offenders))))
        check("vault/crypto.py draws its random bytes from the secrets module", uses_secrets)
        check("vault/crypto.py does not import the predictable random module", not prng_imports,
              ", ".join(prng_imports))
        check("hmac.compare_digest is called for the MAC check and for the password check "
              "(at least two call sites)", compare_sites >= 2, "found %d call site(s)" % compare_sites)

    cli_path = os.path.join(BASE, "vault", "cli.py")
    with open(cli_path, "r", encoding="utf-8") as fh:
        cli_source = fh.read()
    legacy_import = re.search(r"^\s*(?:from\s+\S*legacy\s+import|import\s+\S*legacy\b"
                              r"|from\s+vault\s+import\s+[^#\n]*\blegacy\b)", cli_source, re.M)
    check("vault/cli.py uses the new module and no longer imports vault.legacy",
          "crypto" in cli_source and legacy_import is None,
          "cli.py still imports the legacy module" if legacy_import else "cli.py does not reference the new module")


# --- 6. the report -------------------------------------------------------------
def report_checks():
    lines = read_report()
    if lines is None:
        return
    check("report/remediation.md exists", True)
    raw = []
    for line in lines:
        match = re.match(r"^\s*(?:[-*]\s+)?`?legacy_weakness`?\s*=\s*(CWE-\d+)\s*\|\s*(.+?)\s*$", line)
        if match:
            raw.append((match.group(1), match.group(2)))
    check("report lists exactly four weaknesses as `legacy_weakness = CWE-n | <symbol>`",
          len(raw) == 4, "found %d such line(s)" % len(raw))
    covered = sorted(cwe.lower() for cwe, _symbol in raw)
    check("the four weakness lines cover CWE-323, CWE-328, CWE-916 and CWE-203 once each",
          covered == ["cwe-203", "cwe-323", "cwe-328", "cwe-916"],
          "the CWE ids on the four lines are: %s" % ", ".join(covered))
    accepted = 0
    for cwe, symbol in raw:
        name = symbol.strip().strip("`*_'\"").rstrip(".;:,")
        name = re.sub(r"\(\)$", "", name).strip().strip("`*_'\"").lower()
        digest = hashlib.sha256(("%s|%s" % (cwe.lower(), name)).encode("utf-8")).hexdigest()
        if digest in WEAKNESS_DIGESTS:
            accepted += 1
        else:
            print("      rejected line: legacy_weakness = %s | %s" % (cwe, symbol))
    check("each weakness line names the vault/legacy.py function that carries it "
          "(CWE-323, CWE-328, CWE-916, CWE-203)", len(raw) == 4 and accepted == 4,
          "format: `legacy_weakness = CWE-<id> | <function name from vault/legacy.py>`, "
          "nothing else on the line")
    counts = key_values(lines, "migrated_records")
    check("migrated_records = <n> appears once and equals the number of records the migration "
          "rewrote (re-derived from the legacy snapshot)",
          len(counts) == 1 and counts[0].isdigit() and int(counts[0]) == len(old_records),
          "found %r" % (counts,))


if crypto is None:
    for label in ("cipher experiments (they need a usable vault/crypto.py)",
                  "password hashing experiments (they need a usable vault/crypto.py)",
                  "the live vault.db and users.db are migrated",
                  "reencrypt_all migrates a legacy vault",
                  "the CLI works on the new format"):
        check(label, False, "skipped: vault/crypto.py is missing or unusable")
else:
    cipher_checks()
    password_checks()
    live_data_checks()
    migration_checks()
    cli_checks()
source_checks()
report_checks()

print()
if failures:
    print("check: %d check(s) FAILED: %s" % (len(failures), "; ".join(failures)))
    sys.exit(1)
print("check: all checks passed")
