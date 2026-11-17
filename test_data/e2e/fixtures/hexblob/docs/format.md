# Flash image format (v1, from the vendor SDK)

All multi-byte integers are **big-endian**.

```
offset  size  field
0       4     magic = 0xDEADBEEF
4       4     section_count
8       10*section_count   section table, one entry per section:
              +0   2   section type
              +2   4   absolute offset of the section payload
              +6   4   payload length in bytes
```

Section types:

- `0x0001` - raw data. Payload is stored verbatim.
- `0x0003` - string table. Payload is a sequence of entries:

```
+0   2   entry_length (n)
+2   n   encoded bytes
```

Each entry's bytes are encoded by XOR-ing every byte with the constant `0x5A`. The decoded
bytes are an ASCII string.
