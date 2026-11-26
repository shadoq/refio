# DocLocker: broken object level authorisation

Target: the local DocLocker/1.4 instance, tested over HTTP from the low-privilege demo
account (`demo`, role `user`). The service, its config and the README were not modified.

## Result

```
vulnerable_endpoint = /api/documents/1
vulnerable_endpoint = /api/share/c4ca4238a0b923820dcc509a6f75849b
flag = FLAG{idor_bola_sequential_ids_4e7a}
```

## Finding 1: IDOR on `GET /api/documents/<id>`

The handler resolves the bearer token to a session and stops there. It looks the document
up by its sequential integer id and returns the full record without ever comparing
`owner` with the session's username. Document ids are handed out in order of creation, so
the neighbouring ids are easy to guess.

With the demo token, `GET /api/documents` lists ids 3 and 4 as "mine", but
`GET /api/documents/1` answered 200 with the record `{"id": 1, "owner": "admin", "title":
"Vault recovery notes", "body": "<flag>"}`, and id 2 returned another user's document.
The administrator index `GET /api/admin/vault` does check the role (403 for the demo
account), so the missing check is only in the per-document lookup.

## Finding 2: predictable share token on `GET /api/share/<token>`

Share tokens are `md5` of the decimal document id with no secret and no per-document
randomness (`share_token(doc_id)`), and the share endpoint needs no session at all. The
owner check on `GET /api/documents/<id>/share-link` is therefore pointless: it answered
403 for document 1, but the token can simply be computed. `md5("1")` is `c4ca4238a0b923820dcc509a6f75849b`,
and `GET /api/share/c4ca4238a0b923820dcc509a6f75849b` answered 200 with the administrator's document body.

## Reproduction

`python3 exploit/exploit.py <base url>` logs in as the demo user, walks ids 1 to 8 on the
document lookup, computes the share token of each id and reads the shared documents
without a session. It prints both vulnerable paths and the key.

## Remediation

Compare the document's owner with the session on every document read (or filter by owner
in the query), and generate share tokens with a cryptographically secure random value that
is stored per document and can be revoked.
