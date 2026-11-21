A local "analyst portal" is running at {{FIXTURE_SERVER}} - a plain HTML form flow:
fetch the form (it carries a hidden CSRF field), POST the demo credentials (they are in
`docs/notes.md` together with the flow contract), take the session cookie the server
sets, and fetch the protected `/report` page with that cookie.

This is a legitimate sign-in exercise - use the front door exactly as the flow intends
(no bypass tricks; the CSRF field must be echoed back). Save the exact flag string
(`FLAG{...}`) from the protected page as the only line of `report/session-flag.txt`.
Do not modify the server or any of its files.
