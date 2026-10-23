#!/usr/bin/env python3
"""Linux PTY smoke checks; not a substitute for Windows Terminal/IME/device acceptance."""
import errno
import fcntl
import os
from pathlib import Path
import pty
import select
import signal
import struct
import subprocess
import sys
import tempfile
import termios
import time

binary = str(Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory(prefix="nearbyim-pty-") as directory:
    subprocess.run([binary, "--profile", directory + "/profile", "config", "set", "theme", "mono"], check=True, capture_output=True)
    master, slave = pty.openpty()
    original = termios.tcgetattr(slave)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
    child = subprocess.Popen([binary, "--profile", directory + "/profile", "--ascii", "--no-color"], stdin=slave, stdout=slave, stderr=slave, env={**os.environ, "TERM": "xterm-256color"})
    output = bytearray()
    def collect(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if select.select([master], [], [], 0.05)[0]:
                try:
                    output.extend(os.read(master, 65536))
                except OSError as error:
                    if error.errno == errno.EIO:
                        return
                    raise
    try:
        collect(0.8)
        assert child.poll() is None, output.decode(errors="replace")
        assert b"NearbyIM" in output and b"Terminal" in output, repr(output[:1500])
        for width, height in [(40, 16), (120, 32), (80, 24)]:
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
            child.send_signal(signal.SIGWINCH)
            collect(0.15)
        # Bracketed paste must remain text, including multiline CJK/ZWJ sequences.
        os.write(master, b"\x1b[200~" + "中文 👩‍💻\n第二行".encode() + b"\x1b[201~")
        collect(0.2)
        # Open and close the palette without discarding the chat draft.
        os.write(master, b"\x10help\r")
        collect(0.2)
        os.write(master, b"\x03")
        child.wait(timeout=5)
        collect(0.1)
        assert child.returncode == 0
        restored = termios.tcgetattr(slave)
        assert restored[3] == original[3], "terminal flags were not restored"
        assert b"\x1b[?1049l" in output, "alternate screen was not restored"
        assert b"\x1b[?2004l" in output, "bracketed paste was not disabled"
        print("PASS PTY: launch, 40/80/120 columns, CJK/ZWJ multiline paste, command palette, Ctrl+C, terminal restoration")
    finally:
        if child.poll() is None:
            child.kill()
            child.wait()
        os.close(master)
        os.close(slave)
