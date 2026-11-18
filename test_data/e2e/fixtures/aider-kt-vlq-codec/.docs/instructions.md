# Instructions

Implement a variable-length quantity codec for unsigned 32-bit integers, used to pack lists of counters into a compact byte stream.

## Format

A value is written as a sequence of 7-bit groups, **least significant group first**. Every byte carries one group in its low 7 bits. The high bit (`0x80`) is the continuation flag: it is set on every byte of a value except the last one.

```text
0        -> 00
127      -> 7F
128      -> 80 01
300      -> AC 02
16384    -> 80 80 01
4294967295 (0xFFFFFFFF) -> FF FF FF FF 0F
```

A value always uses the fewest bytes possible. A list is encoded by concatenating the encodings of its values. The empty list encodes to the empty byte list.

## API

```kotlin
object Vlq {
    fun encode(values: List<UInt>): List<UByte>
    fun decode(bytes: List<UByte>): List<UInt>
}
```

## Decoding errors

`decode` throws `IllegalArgumentException` when the input is not a valid stream:

- the last value is incomplete (its final byte still has the continuation flag set);
- a value does not fit in 32 bits (a fifth byte may only carry its low 4 bits, and a value may not have a sixth byte);
- a value is not minimal, i.e. its last group is `0` although the value has more than one byte (for example `80 00`).

The empty byte list decodes to the empty list.
