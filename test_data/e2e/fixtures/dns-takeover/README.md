# EdgeStats

The main web app of the demo environment, Python standard library only.

- `server/app.py` - the service. It routes on the Host header: `app.example.test` is the
  real app (`/` and `/api/stats`); the routing table `server/config/vhosts.json` decides
  which hosts this deployment answers for, and unknown hosts get a plain 404.
- `server/config/vhosts.json` - the vhost routing table.
- `checks.py` - the offline gate (config / routing state + report), run it with
  `python3 checks.py`.
- `report/` - incident write-ups live here.

History: this box used to host the old marketing site under
`old-app.example.test`. That name was decommissioned, but its DNS record was never
removed and still points here. External monitoring keeps flagging the old name.
