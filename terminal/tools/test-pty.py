#!/usr/bin/env python3
"""Real PTY onboarding/help/input checks; not physical Windows/IME acceptance."""
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import re
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
    profile = Path(directory) / "profile"
    master, slave = pty.openpty()
    original = termios.tcgetattr(slave)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
    child = subprocess.Popen([binary, "--profile", str(profile), "--ascii", "--no-color"],
                             stdin=slave, stdout=slave, stderr=slave,
                             env={**os.environ, "TERM": "xterm-256color", "LC_ALL": "en_US.UTF-8"})
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
    def until(text, start=0):
        expected = text.encode()
        deadline = time.monotonic() + 5
        def visible():
            return re.sub(rb"\x1b\[[0-?]*[ -/]*[@-~]", b"", output[start:])
        while expected not in visible() and time.monotonic() < deadline:
            assert child.poll() is None, output.decode(errors="replace")
            collect(0.05)
        assert expected in visible(), (text, visible().decode(errors="replace"))
    def command(text, expected):
        start = len(output)
        os.write(master, b"\x10" + text.encode() + b"\r")
        until(expected, start)
    try:
        until("Interface language")
        os.write(master, b"zh-CN\n")
        until("昵称")
        os.write(master, "测试电脑\n".encode())
        until("接收目录")
        os.write(master, (directory + "/received\n").encode())
        until("开启局域网接收")
        os.write(master, b"no\n")
        until("自动接收")
        os.write(master, b"no\n")
        until("搜索附近设备")
        cfg = json.loads((profile / "config.json").read_text())
        assert cfg["language"] == "zh-CN" and cfg["nickname"] == "测试电脑"
        assert cfg["lan_listen"] is False and cfg["auto_receive_trusted"] is False
        assert b"Nickname" not in output and b"Receive directory" not in output
        for width, height in [(40, 16), (45, 24), (120, 32), (80, 24)]:
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
            child.send_signal(signal.SIGWINCH)
            collect(0.15)
        # Pasted text must remain an editable draft, never a palette command.
        os.write(master, b"\x1b[200~" + "中文 👩‍💻\n第二行".encode() + b"\x1b[201~")
        collect(0.2)
        command("help", "聊天与设备")
        start=len(output)
        for _ in range(4):
            os.write(master,b"\x1b[6~")
            collect(0.15)
        until("身份与安全",start)
        os.write(master,b"\x1b[5~"*4)
        collect(0.15)
        command("set language en", "CHAT & DEVICES")
        command("set language zh-CN", "聊天与设备")
        assert json.loads((profile / "config.json").read_text())["language"] == "zh-CN"
        os.write(master, b"\x1b")
        collect(0.15)
        os.write(master, b"\x03")
        child.wait(timeout=5)
        collect(0.1)
        assert child.returncode == 0
        restored = termios.tcgetattr(slave)
        assert restored[3] == original[3], "terminal flags were not restored"
        assert b"\x1b[?1049l" in output, "alternate screen was not restored"
        assert b"\x1b[?2004l" in output, "bracketed paste was not disabled"
        print("PASS PTY: Chinese onboarding/help, live language persistence, 40/45/80/120 columns, CJK/ZWJ paste, palette, Ctrl+C and terminal restoration")
    finally:
        if child.poll() is None:
            child.kill()
            child.wait()
        os.close(master)
        os.close(slave)
