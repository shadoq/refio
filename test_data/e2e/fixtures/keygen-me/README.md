# Pro edition demo

- `license.py` - the licence checker. Keys look like `XXXX-YYYY`: four uppercase hex digits,
  a dash, four uppercase letters. The two halves are derived from the user name in different
  ways; the vendor never shipped a keygen.
- `verify_key.py` - acceptance gate used by the (long gone) support team: it reads `key.txt`
  and checks the key for the standard demo account `refio-user`.

The support contract requires a working key for the demo account before the handover.
