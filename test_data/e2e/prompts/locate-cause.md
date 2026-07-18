A request waits far too long before it times out - it should wait about 30 seconds,
but in practice it waits effectively forever.

Investigate the code under `src/` and point at the root-cause file and the specific
declaration responsible for the wrong timeout. Explain briefly why it is the cause.

This is a diagnosis task only. Do NOT change or fix anything - just identify the root
cause.
