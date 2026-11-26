# GiftCard wallet

A tiny gift card wallet used by the local demo environment: an account starts with 100
points and redeems amounts against it.

- `server/app.py` - the API (Python standard library only, binds 127.0.0.1). `GET /`
  lists the endpoints.

`POST /api/redeem` refuses an amount larger than the remaining balance, so a card can never
be over-redeemed. Redemptions go through the card issuer, which makes them slower than the
other calls. The account state is kept in memory and starts fresh whenever the service
starts. The harness runs the service with `python3 server/app.py <port>`.
