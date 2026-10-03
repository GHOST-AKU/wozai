#!/usr/bin/env python3
"""Launch the actual packaged binary and a freedesktop menu entry on an X11 display.

The fixture owns its profile and an extracted read-only package. It never opens
the developer's existing chat data. Pillow is needed only to export screenshots.
"""
import ctypes as c
import ctypes.util
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'desktop/build'


def await_condition(condition, message, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(0.1)
    raise AssertionError(message)


class ClientData(c.Union):
    _fields_ = [('b', c.c_char * 20), ('s', c.c_short * 10), ('l', c.c_long * 5)]


class ClientEvent(c.Structure):
    _fields_ = [('type', c.c_int), ('serial', c.c_ulong), ('send_event', c.c_int),
                ('display', c.c_void_p), ('window', c.c_ulong), ('message_type', c.c_ulong),
                ('format', c.c_int), ('data', ClientData)]


class XEvent(c.Union):
    _fields_ = [('client', ClientEvent), ('pad', c.c_long * 24)]


class X11:
    def __init__(self):
        self.lib = c.CDLL(ctypes.util.find_library('X11'))
        self.lib.XOpenDisplay.argtypes = [c.c_char_p]
        self.lib.XOpenDisplay.restype = c.c_void_p
        self.display = self.lib.XOpenDisplay(None)
        if not self.display:
            raise RuntimeError('Run this test in an X11 desktop or xvfb-run.')
        self.lib.XInternAtom.argtypes = [c.c_void_p, c.c_char_p, c.c_int]
        self.lib.XInternAtom.restype = c.c_ulong
        self.lib.XSendEvent.argtypes = [c.c_void_p, c.c_ulong, c.c_int, c.c_long, c.POINTER(XEvent)]
        self.lib.XFlush.argtypes = [c.c_void_p]
        self.lib.XCloseDisplay.argtypes = [c.c_void_p]

    def close_window(self, window):
        event = XEvent()
        event.client.type = 33
        event.client.display = self.display
        event.client.window = window
        event.client.message_type = self.lib.XInternAtom(self.display, b'WM_PROTOCOLS', False)
        event.client.format = 32
        event.client.data.l[0] = self.lib.XInternAtom(self.display, b'WM_DELETE_WINDOW', False)
        self.lib.XSendEvent(self.display, window, False, 0, c.byref(event))
        self.lib.XFlush(self.display)

    def close(self):
        self.lib.XCloseDisplay(self.display)


def windows():
    tree = subprocess.check_output(['xwininfo', '-root', '-tree'], text=True)
    return [(int(window, 16), title) for window, title in re.findall(r'(0x[0-9a-f]+) "([^"]*)"', tree)]


def chat_window():
    for window, title in windows():
        if title == '我在':
            metadata = subprocess.check_output(['xprop', '-id', hex(window), 'WM_CLASS'], text=True)
            if 'dev-ghost-wozai-Main' in metadata:
                return window
    return None


def screenshot(window, output):
    from PIL import ImageGrab
    text = subprocess.check_output(['xwininfo', '-id', hex(window)], text=True)
    def metric(key):
        return int(re.search(re.escape(key) + r'\s+(-?\d+)', text).group(1))
    x, y = metric('Absolute upper-left X:'), metric('Absolute upper-left Y:')
    width, height = metric('Width:'), metric('Height:')
    ImageGrab.grab(bbox=(x, y, x + width, y + height), xdisplay=os.environ['DISPLAY']).save(output)
    return width, height


def launch_desktop(entry, environment):
    gio = c.CDLL(ctypes.util.find_library('gio-2.0'))
    gio.g_desktop_app_info_new_from_filename.argtypes = [c.c_char_p]
    gio.g_desktop_app_info_new_from_filename.restype = c.c_void_p
    gio.g_app_launch_context_new.restype = c.c_void_p
    gio.g_app_launch_context_setenv.argtypes = [c.c_void_p, c.c_char_p, c.c_char_p]
    gio.g_app_info_launch.argtypes = [c.c_void_p, c.c_void_p, c.c_void_p, c.POINTER(c.c_void_p)]
    app = gio.g_desktop_app_info_new_from_filename(os.fsencode(entry))
    if not app:
        raise AssertionError('GIO rejected the generated desktop entry')
    context = gio.g_app_launch_context_new()
    gobject = c.CDLL(ctypes.util.find_library('gobject-2.0'))
    gobject.g_object_unref.argtypes = [c.c_void_p]
    try:
        for key in ('JAVA_TOOL_OPTIONS', 'XDG_DATA_HOME', 'DISPLAY'):
            gio.g_app_launch_context_setenv(context, key.encode(), environment[key].encode())
        error = c.c_void_p()
        if not gio.g_app_info_launch(app, None, context, c.byref(error)):
            raise AssertionError('GIO could not execute the desktop entry')
    finally:
        gobject.g_object_unref(context)
        gobject.g_object_unref(app)


def main():
    archives = sorted(BUILD.glob('NearbyIM-*-linux-*.tar.gz'))
    if len(archives) != 1:
        raise AssertionError('Expected exactly one Linux tar.gz package')
    x11 = X11()
    process = None
    active_window = None
    try:
        with tempfile.TemporaryDirectory(prefix='nearbyim-package-') as temporary:
            fixture = Path(temporary)
            with tarfile.open(archives[0]) as archive:
                archive.extractall(fixture, filter='data')
            package = fixture / 'NearbyIM O\'Brien $HOME %f "中文" \\ folder'
            (fixture / 'NearbyIM').rename(package)
            for path in package.rglob('*'):
                mode = 0o555 if path.is_dir() or os.access(path, os.X_OK) else 0o444
                path.chmod(mode)
            package.chmod(0o555)
            try:
                env = os.environ.copy()
                env['XDG_DATA_HOME'] = str(fixture / 'user-data')
                env['JAVA_TOOL_OPTIONS'] = '-Duser.home="' + str(fixture / 'user-home') + '" -Duser.language=zh -Duser.country=CN'
                profile = Path(env['XDG_DATA_HOME']) / 'wozai'
                binary = package / 'bin/NearbyIM'
                process = subprocess.Popen([binary, '--text-diagnostics'], cwd=fixture, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                active_window = await_condition(chat_window, 'Packaged launcher did not display its native chat window')
                identity = profile / 'identity.properties'
                await_condition(lambda: identity.is_file(), 'Packaged launcher did not create the XDG identity')
                before = identity.read_bytes()
                assert stat.S_IMODE(profile.stat().st_mode) == 0o700
                assert stat.S_IMODE(identity.stat().st_mode) == 0o600
                assert not (package / 'data').exists()
                report = profile / 'text-rendering.txt'
                await_condition(lambda: report.exists(), 'Bundled runtime did not produce rendering evidence')
                assert 'Noto Sans CJK SC' in report.read_text()
                shutil.copyfile(report, BUILD / 'linux-package-text-rendering.txt')
                width, height = screenshot(active_window, BUILD / 'linux-package-window.png')
                assert width >= 760 and height >= 540
                x11.close_window(active_window)
                assert process.wait(timeout=15) == 0, 'Native launcher did not exit cleanly'
                active_window = None
                _, errors = process.communicate()
                assert b'Syntax error' not in errors, 'Packaged launcher queried install paths through an incorrectly quoted shell command'
                assert identity.read_bytes() == before
                result = subprocess.run([binary, '--install-desktop'], cwd=fixture, env=env, capture_output=True, text=True, timeout=15)
                assert result.returncode == 0, 'Desktop registration failed: ' + result.stderr
                entry = Path(env['XDG_DATA_HOME']) / 'applications/nearbyim.desktop'
                subprocess.run(['desktop-file-validate', entry], check=True, capture_output=True)
                # GIO parses the Exec quoting and launches the actual binary. A path
                # contains $, %, quotes, spaces, apostrophes, backslashes and Unicode.
                launch_desktop(entry, env)
                active_window = await_condition(chat_window, 'Desktop entry quoting did not launch the moved package')
                assert identity.read_bytes() == before, 'Desktop launch generated a replacement identity'
                x11.close_window(active_window)
                await_condition(lambda: active_window not in [window for window, _ in windows()], 'Menu-launched application did not exit')
                active_window = None
                assert (package / 'lib/runtime/legal/java.base/LICENSE').is_file()
                assert (package / 'lib/app/libwozai_bluetooth.so').is_file()
                print('Linux package smoke: actual launcher, bundled runtime, native window, CJK font, read-only moved program folder, private XDG identity, unchanged identity on restart, GIO desktop-entry escaping, runtime license and clean exit passed')
            finally:
                if active_window:
                    x11.close_window(active_window)
                if process and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=5)
                package.chmod(0o755)
                for path in package.rglob('*'):
                    if path.is_dir():
                        path.chmod(0o755)
    finally:
        x11.close()


if __name__ == '__main__':
    main()
