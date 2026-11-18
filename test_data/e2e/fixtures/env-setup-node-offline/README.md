# pingboard

A tiny status service. It has no dependencies, so nothing needs to be installed.

## Run

1. Create your local settings: copy `.env.example` to `.env`.
2. Start it with `npm start`.
3. Open `http://127.0.0.1:<PORT>/health`.

## Settings

| Variable   | Meaning                                  |
|------------|------------------------------------------|
| `APP_NAME` | Name reported by the health endpoint.    |
| `PORT`     | TCP port to listen on (loopback only).   |
| `GREETING` | Free text echoed by the health endpoint. |

Settings already present in the process environment win over the values in `.env`.

`npm run check` starts the service the way `npm start` does and verifies the health endpoint. It never leaves the machine.
