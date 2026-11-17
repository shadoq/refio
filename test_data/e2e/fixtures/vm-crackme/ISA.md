# Tiny stack VM - instruction set

`program.bin` is a program for the VM implemented in `vm.py`. Programs are
whitespace-separated integers; each word is either an opcode or an immediate operand of the
preceding opcode. Execution starts at word index 0.

The machine has one integer stack. `INPUT` reads the optional command-line input string
(`python3 vm.py program.bin <input>`); without one, every `INPUT` pushes 0.

| opcode | name    | operands | behaviour |
|-------:|---------|----------|-----------|
| 0      | HALT    | -        | stop execution |
| 1      | PUSH    | n        | push the immediate n |
| 2      | ADD     | -        | pop b, pop a, push a + b |
| 3      | SUB     | -        | pop b, pop a, push a - b |
| 4      | XOR     | -        | pop b, pop a, push a XOR b (bitwise) |
| 5      | EQ      | -        | pop b, pop a, push 1 if a == b else 0 |
| 6      | JMP     | a        | continue at word index a |
| 7      | JNZ     | a        | pop v; continue at word index a when v != 0 |
| 8      | PRINT   | -        | pop v; append chr(v) to the output |
| 9      | PRINTNL | -        | append a newline to the output |
| 10     | INPUT   | i        | push ord(input[i]); push 0 when i is past the end of the input |

Jump targets are absolute word indices: every word of the program counts, opcode words and
immediate words alike.

Worked example - the program `1 65 8 9 0` is `PUSH 65; PRINT; PRINTNL; HALT` and prints `A`
followed by a newline. Reading word by word: `10 0 1 100 4 8 9 0` is `INPUT 0; PUSH 100;
XOR; PRINT; PRINTNL; HALT` - it prints the input's first character XOR 100.
