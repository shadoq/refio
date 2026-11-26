# GiftCard wallet: time-of-check to time-of-use race on `POST /api/redeem`

Target: the local GiftCard/0.9 instance, attacked over HTTP only. The account starts with
100 points and a fresh state on every start of the service. The service and the README were
not modified.

## Result

```
final_balance = -200
flag = FLAG{race_condition_double_spend_0000}
```

## The window

`POST /api/redeem` runs three steps that are not protected by a lock:

1. Check: `STATE["balance"] < amount` answers 409 "insufficient balance".
2. Delay: `time.sleep(ISSUER_ROUND_TRIP_SECONDS)`, 150 ms, while the service "talks to the
   issuer".
3. Write: `STATE["balance"] -= amount`, with the balance that was checked 150 ms earlier.

The service is a `ThreadingHTTPServer`, so requests that arrive inside those 150 ms are
all checked against the same balance and all pass. Each one then subtracts its amount.

## Reproduction

`python3 exploit/exploit.py <base url>` first sends two sequential redeems of 60 as a
control: the first answers 200 (balance 40), the second is refused with 409. It then
starts 6 threads that wait on a barrier and redeem 40 each at the same instant. All six
answered 200, the balance went from 40 to -200, and `GET /api/state` then carries the
issuer notice with the audit key.

## Remediation

Do the check and the write atomically: take a lock (or a compare-and-swap on the stored
balance) before calling the issuer, or reserve the amount first and confirm it afterwards.
