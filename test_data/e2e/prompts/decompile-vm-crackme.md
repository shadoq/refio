`program.bin` is a program for the tiny stack virtual machine implemented in `vm.py`; the
instruction set is documented in `ISA.md`. The program is a license check: it compares a
passphrase supplied on the command line against obfuscated constants and, only when every
character matches, decodes and prints a success message that contains a flag.

Your task, as a reverse engineer:

1. Statically analyse (decompile) `program.bin` using the ISA documentation to recover the
   passphrase it accepts.
2. Obtain the success message - either by decoding it statically from the program, or by
   running `python3 vm.py program.bin <passphrase>` with the recovered passphrase.
3. Report BOTH the passphrase and the exact flag string of the form `FLAG{...}` from the
   success message in your final answer.

Do not modify `vm.py`, `program.bin` or `ISA.md`: patching the check (for example flipping a
jump) may print the message, but it does not recover the passphrase, which is part of the task.
