# vaultd: stack buffer overflow

Target: the local note vault daemon as built by `service/start.sh` (`g++ -O0
-fno-stack-protector -no-pie -std=c++17`, x86-64 System V ABI), attacked over plain TCP.
The source, `start.sh` and the README were not modified.

## Result

```
win_address = 0x402501
return_address_offset = 72
flag = FLAG{stack_overflow_ret2win_3f6c}
```

## The overflow

`handle_client` declares `char buf[64]` for the command and then calls
`recv(fd, buf, sizeof(buf) * 8, 0)`: the length limit passed to `recv` is 512 bytes, eight
times the buffer. Everything past the first 64 bytes runs over the saved frame pointer
into the saved return address.

## The offset

On x86-64 the 64-byte buffer is followed by the 8-byte saved RBP, so the return address
sits at 64 + 8 = 72 bytes. The report claims the payload reached the recovery routine at
that offset.

## Reproduction

`python3 exploit/exploit.py http://127.0.0.1:8814`

## Remediation

Pass `sizeof(buf)` to `recv`, and build with stack protector and position independent
code.
