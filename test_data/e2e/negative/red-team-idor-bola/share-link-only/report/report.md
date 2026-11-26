# DocLocker: predictable share token

Target: the local DocLocker/1.4 instance, tested over HTTP without any account.

## Result

```
vulnerable_endpoint = /api/share/c4ca4238a0b923820dcc509a6f75849b
flag = FLAG{idor_bola_sequential_ids_4e7a}
```

## Finding: predictable share token on `GET /api/share/<token>`

Share tokens are `md5` of the decimal document id with no secret and no per-document
randomness, and the share endpoint needs no session at all. Enumerating ids 1 to 8 by
their computed token, `GET /api/share/c4ca4238a0b923820dcc509a6f75849b` (md5 of "1") answered 200 with the
administrator's document body, which carries the recovery key.

## Reproduction

`python3 exploit/exploit.py <base url>` computes the token of each id and reads the shared
documents.

## Remediation

Generate share tokens with a cryptographically secure random value that is stored per
document and can be revoked.
