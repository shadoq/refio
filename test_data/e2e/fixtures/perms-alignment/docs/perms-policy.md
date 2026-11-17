# Permission policy - app host (authoritative)

The config-management pipeline applies host permissions from the single manifest
`etc/perms.csv` (columns: path, mode, owner, group). The manifest has drifted from the
policy below.

| path                | mode | owner | group | rationale                          |
|---------------------|-------|-------|-------|------------------------------------|
| etc/secrets.env     | 0640  | root  | app   | secret material, group-readable    |
| etc/app.conf        | 0644  | root  | root  | public configuration               |
| var/lib/app/cache   | 2770  | app   | app   | shared scratch, setgid directory   |
| opt/app/bin/run.sh  | 0750  | root  | app   | privileged launcher                |
| var/log/app/app.log | 0640  | app   | app   | service log                        |

Global rule: **no** manifest entry may carry a world-writable bit (last octal digit 2, 3,
6 or 7). Exactly the five rows above, one row per path, no extras.
