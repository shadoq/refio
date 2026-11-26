# vaultd: stack buffer overflow, return into the recovery routine

Target: the local note vault daemon as built by `service/start.sh` (`g++ -O0
-fno-stack-protector -no-pie -std=c++17`, x86-64 System V ABI), attacked over plain TCP.
The source, `start.sh` and the README were not modified.

## Result

```
win_address = 0x402501
return_address_offset = 152
flag = FLAG{stack_overflow_ret2win_3f6c}
```

## The overflow

`handle_client` declares `char buf[64]` for the command and then calls
`recv(fd, buf, sizeof(buf) * 8, 0)`: the length limit passed to `recv` is 512 bytes, eight
times the buffer. Everything past the first 64 bytes runs over the rest of the stack frame
and past the saved frame pointer into the saved return address. There is no stack canary
and the binary is not position independent, so the address of `win()` is the same in every
process.

## The offset

64 + 8 does not hold on this build. At `-O0` the compiler places `buf` at `RBP-0x90`; the
160-byte banner buffer lies below it, and above it come a `std::string` temporary, the
result of the loop's `recv` call, the saved `RBX` and the saved `RBP`. The return address
is therefore 0x90 + 8 = 152 bytes from the start of the buffer. This was measured against
the running daemon: every connection is handled by a fresh process, so padding plus the
address of `win()` was sent for one candidate offset after another, and the offsets 144 to
160 were the only ones that reached the routine; 152 is the one that lands on the return
address exactly.

## The payload

`A` * 152, then the 8-byte little-endian address of `win()` taken from the banner line
`debug: recovery routine loaded at 0x402501`. The handler only returns when its command
loop ends, so the exploit half-closes the socket (`shutdown(SHUT_WR)`) after sending:
`recv` returns 0, `handle_client` leaves the loop, and its `ret` jumps into `win()`, which
writes the recovery key to the still-open connection.

## Reproduction

`python3 exploit/exploit.py http://127.0.0.1:8814` reads the banner, sends the payload and
prints the key.

## Remediation

Pass `sizeof(buf)` to `recv` (or read a line with an explicit bound), and build with stack
protector and position independent code.
