Create a complete single self-contained file `chip8_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a working CHIP-8 emulator.

Implement the interpreter properly: 4 KB of memory with the font set loaded at 0x050 and programs at 0x200,
sixteen 8-bit registers V0 to VF, the 16-bit index register, the program counter, a call stack, and the
delay and sound timers counting down at 60 Hz. Decode and execute the complete instruction set of 35
instructions, including the arithmetic and logic group with the carry and borrow flags in VF, the shift
instructions, jumps and calls, the random instruction, BCD conversion, register save and load, and the key
tests.

The display is 64 by 32 pixels drawn on a canvas scaled to a readable size, with sprites drawn by XOR and
the collision flag set in VF. The 16-key keypad is mapped to a 4 by 4 block of keyboard keys shown in the
interface; key press and release must both reach the emulator.

Include a small demo ROM as a byte array inside the file: a real program, assembled by you, that draws a
sprite and moves or reacts to a key, so that the emulator can be tried without any download. Also allow
loading a ROM from a local file.

Add run, pause, single step and reset controls, an adjustable instructions-per-frame speed, and a debugger
panel showing the registers, the index register, the program counter, the stack and the decoded current
instruction. Every visible control must work. Deliver the one file `chip8_model_01.html`.
