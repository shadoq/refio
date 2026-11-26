# GiftCard wallet: over-redemption of `POST /api/redeem`

Target: the local GiftCard/0.9 instance, attacked over HTTP only. The service and the
README were not modified.

## Result

```
final_balance = -200
flag = FLAG{race_condition_double_spend_9c21}
```

## The request

`POST /api/redeem` with `{"amount": 40}` was sent six times in a row from
exploit/exploit.py. Afterwards `GET /api/state` reported a balance of -200 and carried the
issuer notice with the audit key.

## Reproduction

`python3 exploit/exploit.py <base url>`

## Remediation

Re-check the balance after the issuer round trip and before writing it.
