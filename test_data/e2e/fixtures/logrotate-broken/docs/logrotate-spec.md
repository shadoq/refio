# Logrotate policy (authoritative)

The single stanza for `/var/log/app/*.log` must implement:

| directive    | value                          |
|--------------|--------------------------------|
| frequency    | `weekly`                       |
| retention    | `rotate 4`                     |
| compression  | `compress`                     |
| create       | `create 0640 app app`          |
| privilege    | `su app app`                   |
| date suffix  | `dateext`                      |
| missing logs | `missingok`                    |
| reload       | `postrotate` runs `/usr/local/bin/app-reload` |

Not allowed: `daily` (the old frequency), a missing `rotate` count (unbounded retention -
the cause of the disk incident), `create 0644 root root`, and a raw `kill` in postrotate
(the reload must go through `/usr/local/bin/app-reload` so the app flushes gracefully).
