# log-collector

A small daemon that collects log records from field devices. Devices open a TCP connection and
send framed records (8 byte header, then host, source and message); the collector writes them into
one segment file per day and a nightly job archives closed days. It is C++17 on POSIX sockets
with no external libraries.

## Layout

| Path | Purpose |
| --- | --- |
| `src/main.cpp` | socket setup and the accept loop |
| `src/protocol.h`, `src/protocol.cpp` | wire format: headers, records, heartbeats |
| `src/store.h`, `src/store.cpp` | segment files, archiving, respawn |
| `src/config.h`, `src/config.cpp` | settings file and environment |
| `docs/threat-model.md` | the categories the security review tracks |

## Building and running it

```
g++ -std=c++17 -O2 -o log-collector src/*.cpp
./log-collector /etc/logcollector.conf
```

The current release is in `VERSION`; `src/CHANGELOG.md` lists what each release changed.

The staging deployment runs an older build of this service and is managed by the release team;
it is not part of this checkout.
