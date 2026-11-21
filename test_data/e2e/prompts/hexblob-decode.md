A firmware image was pulled off a device during an internal security review:

- `firmware/flash_dump.hex` - the first 256 bytes of the image as a classic offset hexdump
  (`00000000: de ad be ef ...`).
- `docs/format.md` - the image format specification recovered from the SDK.
- `README.md` - engagement notes.

Your task: statically decode the dump per the format documentation and recover the strings
held in the image's string table. Report every recovered string in your final answer,
including any flag of the form `FLAG{...}`. Do not modify the dump, the format documentation
or any other project file - this is analysis, not patching.
