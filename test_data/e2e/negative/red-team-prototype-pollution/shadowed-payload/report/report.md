# ThemeHub preferences service: prototype pollution in `PUT /api/settings`

Target: the local ThemeHub preferences instance, attacked over HTTP only. The service and
the README were not modified.

## Result

```
vulnerable_endpoint = /api/settings
flag = FLAG{prototype_pollution_merge_5d8b}
```

## The merge

`PUT /api/settings` parses the JSON body and hands it to `deepMerge(settings, patch)`. The
merge iterates `Object.keys(patch)`, and `JSON.parse` keeps `"__proto__"` as an ordinary
own key. For an object value the merge descends into `target[key]` without asking what
that is, so for the key `__proto__` it descends into `Object.prototype`.

## The payload

`{"__proto__": {"enabled": {"adminPanel": true}}}` was accepted with 200 and wrote
`Object.prototype.enabled.adminPanel`. The report claims the admin panel was switched on
for the demo account by this patch and that `GET /debug/flag` then answered with the
debug key.

## Reproduction

`python3 exploit/exploit.py <base url>`

## Remediation

Reject the keys `__proto__`, `constructor` and `prototype` in the merge.
