# Reconnaissance report - webhook-relay

Scope: the source in `src` and the staging instance.

## Passive reconnaissance (source review)

passive_finding = CWE-22 | src/spool.ts | spoolPath
passive_finding = CWE-78 | src/alerts.ts | notifyChat
passive_finding = CWE-347 | src/signatures.ts | verifySignature
passive_finding = CWE-209 | src/errors.ts | renderErrorPage

- **CWE-22 Path traversal** (`spoolPath` in `src/spool.ts`): the event id of the sender becomes a file name under the inbox without any check, so a delivery can be written outside the spool directory

- **CWE-78 OS command injection** (`notifyChat` in `src/alerts.ts`): the text of the chat post is put into a command string that runs through a shell; the topic of an event reaches it

- **CWE-347 Improper verification of cryptographic signature** (`verifySignature` in `src/signatures.ts`): a header whose `alg` is "none" is accepted as valid, so a partner delivery needs no signature at all

- **CWE-209 Error message containing sensitive information** (`renderErrorPage` in `src/errors.ts`): the page sent for an unexpected failure carries the stack trace and the whole runtime configuration, the tenant secret included

## Active reconnaissance (staging instance)

instance_id = a1b2c3d4e5f6
staging_version = 1.9.3
source_version = 2.1.0
undocumented_endpoint = /internal/runtime
exposed_backup = webhook-relay-1.9.3-2026-11-23.tar.gz
traversal_leak = wr-stg-9d4a7e21c8b3

- `/health` answers `{"service": "webhook-relay", "version": "1.9.3", ...}` with a fresh `instance` value, so staging runs behind the source (2.1.0).
- `/robots.txt` disallows `/internal/` and `/retention/`; `/internal/runtime` is the diagnostics page that 2.0.0 removed and answers 200 on staging, `/retention/` lists three archives of which the 2026-11-23 one is the newest.
- `/archive?name=..` lists the parent directory and names `relay.env`; `/archive?name=../relay.env` returns the deployment settings with the signing token on 1.9.3, although 2.1.0 fixed exactly that in `archivePath`: the fix has not been deployed.
- `/dashboard` answers 403 from the network policy and is out of scope.
