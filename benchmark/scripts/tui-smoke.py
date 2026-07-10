#!/usr/bin/env python3
# Run with uv (no venv needed):
#   uv run --with pexpect --with pyte benchmark/scripts/tui-smoke.py
#
# tui-smoke.py - pty smoke test for the Refio TUI.
#
# Spawns the installed CLI (cli/build/install/cli/bin/cli) inside a pseudo-terminal,
# feeds its output into a pyte VT100 screen emulator, and runs a sequence of scenes.
# Each scene sends keys and asserts on the rendered screen and/or the CLI log file.
#
# Isolation (CRITICAL): the TUI is pointed at a throwaway HOME via
# JAVA_OPTS="-Duser.home=<tmp>". Overriding the HOME environment variable does NOT
# work - the JVM reads user.home from the OS, not from $HOME. Without this the smoke
# would read/write the real ~/.refio database and config.
#
# The --project dir is a fresh temp dir, so nothing real is touched.
#
# Exit codes: 0 = all scenes passed (expected-fail scenes may fail unless --strict),
#             1 = a scene failed, 2 = CLI dist missing or spawn error.

import argparse
import os
import re
import shutil
import sys
import tempfile
import time

try:
    import pexpect
    import pyte
except ImportError:
    print("Missing deps. Run via:")
    print("  uv run --with pexpect --with pyte benchmark/scripts/tui-smoke.py")
    sys.exit(2)

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CLI_PATH = os.path.join(REPO_ROOT, "cli", "build", "install", "cli", "bin", "cli")

COLS, ROWS = 120, 35

# Key escape sequences (xterm)
K = {
    "F1": "\x1bOP",
    "F2": "\x1bOQ",
    "F3": "\x1bOR",
    "F4": "\x1bOS",
    "F5": "\x1b[15~",
    "F6": "\x1b[17~",
    "F7": "\x1b[18~",
    "F8": "\x1b[19~",
    "F9": "\x1b[20~",
    "UP": "\x1b[A",
    "DOWN": "\x1b[B",
    "RIGHT": "\x1b[C",
    "LEFT": "\x1b[D",
    "SHIFT_TAB": "\x1b[Z",
    "CTRL_Q": "\x11",
    "ALT_H": "\x1bh",
    "ENTER": "\r",
    "ESC": "\x1b",
}


class Tui:
    """Wraps a pexpect child + pyte screen and pumps output between them."""

    def __init__(self, cli, project_dir, home_dir):
        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.ByteStream(self.screen)
        env = dict(os.environ)
        # Java reads user.home from the OS; env HOME alone does not isolate.
        env["JAVA_OPTS"] = f"-Duser.home={home_dir}"
        env["HOME"] = home_dir  # belt and braces for non-JVM helpers
        env["TERM"] = "xterm-256color"
        self.child = pexpect.spawn(
            cli, ["-p", project_dir],
            env=env, dimensions=(ROWS, COLS), timeout=5, encoding=None,
        )
        self.log_path = os.path.join(home_dir, ".refio", "refio-cli.log")

    def pump(self, seconds=1.5):
        """Read child output for up to `seconds`, feeding it to the emulator."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                data = self.child.read_nonblocking(size=65536, timeout=0.25)
                if data:
                    self.stream.feed(data)
            except pexpect.TIMEOUT:
                pass
            except pexpect.EOF:
                break

    def send(self, keys, settle=0.6):
        for key in keys:
            self.child.send(K.get(key, key).encode())
            self.pump(settle)

    def display(self):
        return "\n".join(self.screen.display)

    def log_text(self):
        try:
            with open(self.log_path, "r", errors="replace") as f:
                return f.read()
        except OSError:
            return ""

    def alive(self):
        return self.child.isalive()

    def resize(self, rows, cols):
        self.child.setwinsize(rows, cols)
        self.screen.resize(rows, cols)


def scene_result(name, ok, detail="", expected_fail=False, strict=False):
    """Print a per-scene verdict line; return True if the scene counts as passing."""
    if ok:
        print(f"PASS  {name}")
        return True
    if expected_fail and not strict:
        print(f"EXPECTED-FAIL  {name}  ({detail})")
        return True
    print(f"FAIL  {name}  ({detail})")
    return False


def check_screen(tui, expect=(), forbid=()):
    disp = tui.display()
    for text in expect:
        if text not in disp:
            return False, f"expected '{text}' on screen"
    for text in forbid:
        if text in disp:
            return False, f"forbidden '{text}' on screen"
    return True, ""


def main():
    ap = argparse.ArgumentParser(description="Refio TUI pty smoke test")
    ap.add_argument("--strict", action="store_true",
                    help="expected-fail scenes count as real failures")
    ap.add_argument("--cli", default=CLI_PATH, help="path to the installed cli binary")
    ap.add_argument("--keep", action="store_true", help="keep the temp dirs for inspection")
    args = ap.parse_args()

    if not os.path.isfile(args.cli) or not os.access(args.cli, os.X_OK):
        print(f"CLI dist not found: {args.cli}")
        print("Build it first: sh gradlew :cli:installDist")
        sys.exit(2)

    project_dir = tempfile.mkdtemp(prefix="refio-smoke-proj-")
    home_dir = tempfile.mkdtemp(prefix="refio-smoke-home-")
    with open(os.path.join(project_dir, "README.md"), "w") as f:
        f.write("# smoke fixture\n")

    print(f"project: {project_dir}")
    print(f"home:    {home_dir}")

    failures = 0
    tui = None
    try:
        tui = Tui(args.cli, project_dir, home_dir)
        tui.pump(8.0)  # JVM + TUI startup

        # Scene 1: startup renders the tab bar.
        ok, why = check_screen(tui, expect=["F1:Help"])
        if not scene_result("startup-tab-bar", ok, why):
            failures += 1
            # Nothing else can meaningfully run without a rendered TUI.
            print("startup failed; aborting remaining scenes")
            sys.exit(1)

        # Scene 2: F1..F9 navigation, then toggle back to chat with the same key.
        nav_ok, nav_why = True, ""
        for fk in ["F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9"]:
            tui.send([fk])
            if not tui.alive():
                nav_ok, nav_why = False, f"process died after {fk}"
                break
            ok, why = check_screen(tui, expect=["F1:Help"])
            if not ok:
                nav_ok, nav_why = False, f"after {fk}: {why}"
                break
            tui.send([fk])  # toggle back
            if not tui.alive():
                nav_ok, nav_why = False, f"process died toggling back from {fk}"
                break
        if not scene_result("fkey-navigation", nav_ok, nav_why):
            failures += 1

        # Scene 3: Settings cursor movement stays on a live screen.
        tui.send(["F9"])
        for _ in range(15):
            tui.send(["DOWN"], settle=0.15)
        tui.pump(1.0)
        ok = tui.alive()
        why = "" if ok else "process died during cursor-down in Settings"
        if ok:
            ok, why = check_screen(tui, expect=["F1:Help"])
        if not scene_result("settings-cursor-down", ok, why):
            failures += 1

        # Scene 4: toggle a boolean field in Settings (Enter on the current field).
        tui.send(["ENTER"])
        ok = tui.alive()
        why = "" if ok else "process died on Settings toggle"
        if not scene_result("settings-toggle-bool", ok, why):
            failures += 1

        # Scene 5: typing lowercase letters in Settings then Esc must not leak into
        # the chat input buffer. Known leak in the current input handler, so this
        # scene is expected-fail until the fix lands (use --strict to enforce).
        marker = "zqzq"
        tui.send(list(marker), settle=0.2)
        tui.send(["ESC"])  # Esc leaves Settings back to the chat screen
        tui.pump(1.0)
        ok, why = check_screen(tui, forbid=[marker])
        if not scene_result("settings-no-typechar-leak", ok, why,
                            expected_fail=True, strict=args.strict):
            failures += 1

        # Scene 6: open a side panel (F8), type a message, Enter -> the log must show
        # the message being sent (the model may be unreachable; a send entry suffices).
        # Known bug: side panels intercept Enter, so expected-fail until fixed.
        msg = "hello-smoke-probe"
        tui.send(["F8"])
        tui.send(list(msg), settle=0.05)
        tui.send(["ENTER"])
        tui.pump(3.0)
        log = tui.log_text()
        ok = bool(re.search(re.escape(msg), log)) or bool(
            re.search(r"(?i)(sending (streaming )?chat request|send(ing)? message|chatRequest)", log))
        why = "no send-message entry in refio-cli.log"
        if not scene_result("panel-send-message-logged", ok, why,
                            expected_fail=True, strict=args.strict):
            failures += 1

        # Scene 7: two terminal resizes with keys in between; the TUI must survive
        # and keep rendering the tab bar.
        tui.resize(28, 100)
        tui.pump(2.0)
        tui.send(["F2"])
        tui.send(["F2"])
        tui.resize(40, 140)
        tui.pump(2.0)
        tui.send(["DOWN"])
        ok = tui.alive()
        why = "" if ok else "process died after resize"
        if ok:
            ok, why = check_screen(tui, expect=["F1:Help"])
        if not scene_result("resize-survives", ok, why):
            failures += 1

        # Scene 8: Ctrl+Q exits cleanly.
        tui.send(["CTRL_Q"])
        deadline = time.time() + 10
        while tui.alive() and time.time() < deadline:
            tui.pump(0.5)
        ok = not tui.alive()
        why = "" if ok else "process still alive 10s after Ctrl+Q"
        if not scene_result("ctrl-q-clean-exit", ok, why):
            failures += 1

    finally:
        if tui is not None and tui.alive():
            tui.child.terminate(force=True)
        if args.keep:
            print(f"kept: {project_dir} {home_dir}")
        else:
            shutil.rmtree(project_dir, ignore_errors=True)
            shutil.rmtree(home_dir, ignore_errors=True)

    if failures:
        print(f"\n{failures} scene(s) failed")
        sys.exit(1)
    print("\nall scenes passed")
    sys.exit(0)


if __name__ == "__main__":
    main()
