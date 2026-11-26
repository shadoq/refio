# Policy format and house rules

## Syntax

A policy is a JSON document with a `Statement` list. Every statement has:

| Field | Meaning |
| --- | --- |
| `Sid` | Free text label (the evaluator only prints it) |
| `Effect` | `Allow` or `Deny` |
| `Principal` | One name, `group:<name>` or `*` |
| `Action` | A string or a list of strings |
| `Resource` | A string or a list of strings |

`*` inside an action or resource matches any sequence of characters, including `:` and `/`
(`files:*` is every files action, `files/runbooks/*` is everything under that prefix).
A statement matches a request when its `Principal`, one of its `Action` patterns and one of
its `Resource` patterns all match. Any matching `Deny` wins, then any matching `Allow`
allows, everything else is denied.

## House rules for a policy change

1. The logs decide: a principal keeps an action only if it used it, and only on the
   resources it used. The access logs are the record of what everybody really needs.
2. One `Allow` statement per principal, written for that principal by its exact name. No
   `Deny` statements (the evaluator denies whatever is not allowed), no `group:` principals
   and no `*` principal in the policy document.
3. No statement may grant everything: the actions `*` and `*:*` are out, and a statement
   never combines a wildcard action with a wildcard resource. Actions are named explicitly.
4. A directory prefix such as `files/runbooks/*` is accepted only for a directory where the
   principal worked on at least three different resources. Otherwise list the exact resources.
5. A principal without a statement can do nothing. Use that for credentials that must be
   switched off.

## Checking a draft

    python3 src/iam/evaluator.py <principal> <action> <resource> --policy policy/policy.json
