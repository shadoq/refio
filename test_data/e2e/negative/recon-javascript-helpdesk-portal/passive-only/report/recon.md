# Reconnaissance report - helpdesk-portal

Scope: the source in `src` and the staging instance.

## Passive reconnaissance (source review)

passive_finding = CWE-79 | src/tickets.js | renderTicketDetail
passive_finding = CWE-352 | src/api.js | postForm
passive_finding = CWE-522 | src/account.js | rememberCredentials
passive_finding = CWE-798 | src/config.js | loadConfig

- **CWE-79 Cross-site scripting** (`renderTicketDetail` in `src/tickets.js`): subject, requester and description of a ticket are interpolated into innerHTML without escaping, so a requester can run script in an agent's browser

- **CWE-352 Cross-site request forgery** (`postForm` in `src/api.js`): the form post sends the session cookie and changes state without the CSRF token the JSON requests carry

- **CWE-522 Insufficiently protected credentials** (`rememberCredentials` in `src/account.js`): the clear text password from the sign-in form is stored in localStorage for the next visit

- **CWE-798 Hard-coded credential** (`loadConfig` in `src/config.js`): a live tenant key is compiled into the configuration loader as the fallback for the page

## Active reconnaissance (staging instance)


- `/health` answers `{"service": "helpdesk-portal", "version": "1.9.3", ...}` with a fresh `instance` value, so staging runs behind the source (2.1.0).
- `/robots.txt` disallows `/internal/` and `/dumps/`; `/internal/agent-tools` is the agent tools page that 2.0.0 removed and answers 200 on staging, `/dumps/` lists three archives of which the 2026-11-23 one is the newest.
- `/download?name=..` lists the parent directory and names `portal.env`; `/download?name=../portal.env` returns the deployment settings with the session secret on 1.9.3, although 2.1.0 fixed exactly that in `attachmentUrl`: the fix has not been deployed.
- `/staff` answers 403 from the network policy and is out of scope.
