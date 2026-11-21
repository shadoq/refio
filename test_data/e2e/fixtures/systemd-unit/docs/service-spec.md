# Service definition (authoritative)

The `orders` service runs under systemd as user `app` from `/opt/app`:

| field             | value                                  |
|-------------------|----------------------------------------|
| ExecStart         | `/usr/bin/python3 /opt/app/server.py`  |
| WorkingDirectory  | `/opt/app`                             |
| User              | `app`                                  |
| Restart           | `always`                               |
| EnvironmentFile   | `/etc/app.env`                         |
| After             | `network.target`                       |

The environment file `/etc/app.env` must define:

- `APP_PORT=8080`
- `TOKEN_FILE=/opt/app/token.txt` (the service refuses to start when the token file is
  missing - it is not optional and there is exactly one valid path).
