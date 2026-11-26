# helpdesk-portal (front-end)

The browser front-end of the ACME helpdesk portal: a sign-in form, the ticket list, the ticket
page with its attachments and a print export. It is vanilla ES2020 in ES modules, with no build
step and no dependencies.

## Layout

| Path | Purpose |
| --- | --- |
| `index.html` | the single page |
| `src/app.js` | start up, the hash router and the periodic refresh |
| `src/api.js` | requests to the portal API, attachment links |
| `src/tickets.js` | the ticket list, the ticket page and the attachments |
| `src/account.js` | the sign-in form and "keep me signed in" |
| `src/config.js` | runtime configuration |
| `src/queue.js` | the status summary panel next to the ticket list |
| `src/refresh.js` | the periodic refresh of the list |
| `docs/threat-model.md` | the categories the security review tracks |

## Running it locally

```
python3 -m http.server 8080
```

The current release is in `src/VERSION`; `src/CHANGELOG.md` lists what each release changed.

The staging deployment runs an older build of this portal and is managed by the release team; it
is not part of this checkout.
