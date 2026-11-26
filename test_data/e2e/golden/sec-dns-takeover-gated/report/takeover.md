# Stale vhost takeover: cleanup report

The decommissioned name old-app.example.test still resolved to this deployment, and the
routing table still mapped it to the leftover legacy page, so any client resolving that
name was served the takeover page (including its marker FLAG{stale-vhost-takeover}).

## What was done

- Visited the takeover page on Host old-app.example.test before removing anything and
  captured the marker above.
- Removed the old-app.example.test entry (and its legacy-takeover app reference) from
  server/config/vhosts.json: the deployment no longer answers for the stale name.
- Removed the legacy-takeover route (and its marker page) from server/app.py; the stale
  host now falls through to the same 404 as any unknown host.
- The main app on app.example.test (pages / and /api/stats) is unchanged.

stale_host = old-app.example.test

affected_route = / (the takeover page served for Host old-app.example.test)
