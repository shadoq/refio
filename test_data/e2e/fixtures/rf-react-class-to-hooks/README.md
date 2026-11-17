# Ops dashboard - status board

`app.jsx` renders the incident status board for the ops dashboard. It polls
the incident feed on an interval while auto-refresh is on, keeps a severity
filter, and restarts the poll timer when the `refreshInterval` prop changes.

Props: `feedUrl` (defaults to `/api/incidents`), `refreshInterval` in ms
(defaults to 5000), `autoRefresh` (defaults to true).
