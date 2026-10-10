"""Real TCP and browser checks for the synthetic-data LAN baseline."""
import argparse
import http.client
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--powershell', default='powershell.exe')
parser.add_argument('--browser-channel', default='')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
size = 64 * 1024 * 1024
command = [args.powershell, '-NoLogo', '-NoProfile', '-NonInteractive', '-File',
           str(root / 'tools/lan-speed.ps1'), '-ListenAddress', '127.0.0.1']
process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, encoding='utf-8', errors='replace')
checks = []
def check(condition, name):
    if not condition:
        raise AssertionError(name)
    checks.append(name)

def request(method, path, body=None, headers=None):
    started = time.monotonic()
    conn = http.client.HTTPConnection('127.0.0.1', url.port, timeout=30)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        data = response.read()
        if len(data) == size or (body is not None and len(body) == size):
            print(f'Raw {method} complete: {time.monotonic() - started:.3f}s', flush=True)
        return response.status, data, dict(response.getheaders())
    except (ConnectionResetError, http.client.RemoteDisconnected):
        return 0, b'', {}
    finally:
        conn.close()

def raw(data):
    with socket.create_connection(('127.0.0.1', url.port), timeout=30) as client:
        client.sendall(data)
        result = bytearray()
        while True:
            try:
                part = client.recv(8192)
            except ConnectionResetError:
                # Windows may reset a rejected request with unread header bytes.
                return bytes(result)
            if not part:
                return bytes(result)
            result.extend(part)

try:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        line = process.stdout.readline()
        found = re.search(r'http://127\.0\.0\.1:\d+/[a-f0-9]+/', line)
        if found:
            url = urlsplit(found.group())
            break
        if process.poll() is not None:
            raise RuntimeError('Server exited before listening: ' + line)
    else:
        raise RuntimeError('No server address received')
    prefix = url.path.rstrip('/')
    code, data, headers = request('GET', url.path)
    check(code == 200 and '局域网测速' in data.decode('utf-8'), 'UTF-8 browser page')
    check(headers.get('Cache-Control') == 'no-store', 'baseline is not cached')
    code, data, _ = request('GET', prefix + '/download')
    check(code == 200 and len(data) == size, 'complete 64 MiB download')
    check(len(set(data[:65536])) > 200, 'synthetic payload has random byte values')
    code, data, _ = request('POST', prefix + '/upload', os.urandom(size))
    check(code == 200 and json.loads(data)['bytes'] == size, 'complete 64 MiB upload and receipt')
    code, _, _ = request('GET', '/other/download')
    check(code == 400, 'unknown token is rejected')
    code, _, _ = request('GET', prefix + '/../private.txt')
    check(code == 400, 'filesystem path is rejected')
    code, _, _ = request('POST', prefix + '/upload', b'a')
    check(code in (0, 400), 'short declared upload is rejected')
    malformed = (f'POST {prefix}/upload HTTP/1.1\r\nHost: localhost\r\n'
                 f'Content-Length: {size}\r\nContent-Length: {size}\r\n\r\n').encode()
    reply = raw(malformed)
    check(not reply or reply.startswith(b'HTTP/1.1 400'), 'duplicate length is rejected')
    malformed = (f'POST {prefix}/upload HTTP/1.1\r\nHost: localhost\r\n'
                 'Transfer-Encoding: chunked\r\n\r\n').encode()
    reply = raw(malformed)
    check(not reply or reply.startswith(b'HTTP/1.1 400'), 'chunked upload is rejected')
    malformed = (f'GET {url.path} HTTP/1.1\r\nX-Long: ' + 'a' * 8200 + '\r\n\r\n').encode()
    reply = raw(malformed)
    check(not reply or reply.startswith(b'HTTP/1.1 400'), 'oversized headers are bounded')
    # A dropped client must not leave the server stuck or stop subsequent tests.
    with socket.create_connection(('127.0.0.1', url.port), timeout=30) as client:
        client.sendall((f'POST {prefix}/upload HTTP/1.1\r\nHost: localhost\r\n'
                        f'Content-Length: {size}\r\n\r\n').encode() + b'partial')
    code, _, _ = request('GET', url.path)
    check(code == 200, 'server recovers after interrupted upload')
    with socket.create_connection(('127.0.0.1', url.port), timeout=30):
        started = time.monotonic()
        code, _, _ = request('GET', url.path)
        check(code == 200 and time.monotonic() - started < 2,
              'idle browser preconnection does not stall a real request')
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        launch = {'headless': True}
        if args.browser_channel:
            launch['channel'] = args.browser_channel
        browser = p.chromium.launch(**launch)
        try:
            page = browser.new_page(viewport={'width': 390, 'height': 844})
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(url.geturl())
            page.click('#down')
            page.wait_for_function("document.querySelector('#result').textContent.includes('MiB/s')", timeout=90000)
            download = page.locator('#result').inner_text()
            check('电脑 → 手机' in download and '64 MiB 完整传输' in download, 'browser download completion and units')
            page.click('#up')
            page.wait_for_function("document.querySelector('#result').textContent.includes('手机 → 电脑：') && document.querySelector('#result').textContent.split('MiB/s').length === 3", timeout=90000)
            results = page.locator('#result').inner_text()
            check(results.count('64 MiB 完整传输') == 2, 'browser upload verifies server receipt')
            check(not errors, 'browser has no JavaScript errors')
            page.screenshot(path=str(root / 'build/lan-speed-browser.png'), full_page=True)
            print(results)
        finally:
            browser.close()
    print(json.dumps({'shell': args.powershell, 'checks': checks, 'count': len(checks)}, ensure_ascii=False))
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
