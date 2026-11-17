#!/usr/bin/env python3
"""A tiny stack virtual machine, used as a bytecode target for the e2e harness.

Programs are plain text: whitespace-separated integers, each word either an opcode or an
immediate operand (see ISA.md for the instruction set). The machine keeps a single stack of
integers and a read-only input string taken from the command line.

Usage: python3 vm.py <program-file> [input-string]
"""
import sys

HALT, PUSH, ADD, SUB, XOR, EQ, JMP, JNZ, PRINT, PRINTNL, INPUT = range(11)

STEP_LIMIT = 200_000


class VMError(Exception):
    pass


def run(program, text=""):
    pc = 0
    stack = []
    steps = 0
    out = []
    while pc < len(program):
        steps += 1
        if steps > STEP_LIMIT:
            raise VMError("step limit exceeded")
        op = program[pc]
        pc += 1
        if op == HALT:
            break
        if op == PUSH:
            stack.append(program[pc])
            pc += 1
        elif op == ADD:
            b = _pop(stack)
            a = _pop(stack)
            stack.append(a + b)
        elif op == SUB:
            b = _pop(stack)
            a = _pop(stack)
            stack.append(a - b)
        elif op == XOR:
            b = _pop(stack)
            a = _pop(stack)
            stack.append(a ^ b)
        elif op == EQ:
            b = _pop(stack)
            a = _pop(stack)
            stack.append(1 if a == b else 0)
        elif op == JMP:
            pc = program[pc]
        elif op == JNZ:
            target = program[pc]
            pc += 1
            if _pop(stack) != 0:
                pc = target
        elif op == PRINT:
            out.append(chr(_pop(stack)))
        elif op == PRINTNL:
            out.append("\n")
        elif op == INPUT:
            idx = program[pc]
            pc += 1
            stack.append(ord(text[idx]) if idx < len(text) else 0)
        else:
            raise VMError(f"bad opcode {op} at word {pc - 1}")
    return "".join(out)


def _pop(stack):
    if not stack:
        raise VMError("stack underflow")
    return stack.pop()


def main():
    if len(sys.argv) < 2:
        print("usage: vm.py <program-file> [input-string]", file=sys.stderr)
        return 2
    try:
        with open(sys.argv[1], "r", encoding="utf-8") as fh:
            program = [int(tok) for tok in fh.read().split()]
    except (OSError, ValueError) as exc:
        print(f"cannot load program: {exc}", file=sys.stderr)
        return 2
    text = sys.argv[2] if len(sys.argv) > 2 else ""
    try:
        sys.stdout.write(run(program, text))
    except VMError as exc:
        print(f"vm error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
