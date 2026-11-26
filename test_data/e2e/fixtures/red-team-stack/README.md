# Note vault daemon (vaultd)

`vaultd` is a small TCP service of the local demo environment: clients open a plain TCP
connection, get a banner, and send one command per message (`HELP`, `NOTE <text>`, `QUIT`).

- `service/vaultd.cpp` - the daemon (C++17, POSIX sockets, one process per connection).
- `service/start.sh` - builds it into a scratch dir and starts it: `bash service/start.sh
  <port>`. It needs `g++`.

The build is deliberately old-fashioned for the demo image: `-O0 -fno-stack-protector
-no-pie -std=c++17` on x86-64 (System V ABI). The daemon binds 127.0.0.1 only. The recovery
routine belongs to support sessions and is not reachable through the command loop.
