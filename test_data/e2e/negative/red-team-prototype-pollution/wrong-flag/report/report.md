# ThemeHub preferences service: prototype pollution in `PUT /api/settings`

Target: the local ThemeHub preferences instance, attacked over HTTP only. The service and
the README were not modified.

## Result

```
vulnerable_endpoint = /api/settings
flag = FLAG{prototype_pollution_merge_0000}
```

## The merge

`PUT /api/settings` parses the JSON body and hands it to `deepMerge(settings, patch)`. The
merge iterates `Object.keys(patch)`, and `JSON.parse` keeps `"__proto__"` as an ordinary
own key. For an object value the merge descends into `target[key]` without asking what
that is, so for the key `__proto__` it descends into `Object.prototype` and every key of
the payload becomes a property of `Object.prototype`. The `"constructor"` key followed by
`"prototype"` reaches the same object, because `settings.constructor` is `Object`.

## Why the obvious payloads do not get the key

- `{"enabled": {"adminPanel": true}}` is refused with 400: the `enabled` section is
  reserved for the staff console.
- `{"__proto__": {"enabled": {"adminPanel": true}}}` is accepted with 200 and does
  write `Object.prototype.enabled`, but the account already has its own `settings.enabled`
  object, which shadows the inherited one. `GET /api/features` still reports the admin
  panel as off and `GET /debug/flag` still answers 403.

## The gadget

`/debug/flag` tests `settings.enabled.adminPanel || Object.prototype.isAdmin`. The second
operand is read from the prototype itself, so nothing shadows it. Sending
`PUT /api/settings` with `{"__proto__": {"isAdmin": true}}` sets `Object.prototype.isAdmin`
and the next `GET /debug/flag` answered 200 with the debug key.

## Reproduction

`python3 exploit/exploit.py <base url>` shows the refused patch, the shadowed payload and
then the working payload, and prints the key.

## Remediation

Reject the keys `__proto__`, `constructor` and `prototype` in the merge (or copy into an
object created with `Object.create(null)`), and never read a privilege decision from a
prototype-inherited property (`Object.hasOwn`).
