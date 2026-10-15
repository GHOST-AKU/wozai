#!/usr/bin/env python3
"""Wrap the existing Linux image in a pinned type-2 runtime; do not rebuild Java."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "desktop"
BUILD = DESKTOP / "build"


def main():
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise SystemExit("The pinned AppImage runtime currently supports Linux x86_64 only")
    spec = json.loads((DESKTOP / "appimage-runtime.json").read_text())
    version = json.loads((ROOT / "i18n/config.json").read_text())["appVersion"]
    image = BUILD / "package/NearbyIM"
    if not (image / "lib/app/nearbyim-desktop.jar").is_file() or (image / "data").exists():
        raise SystemExit("A complete, profile-free packaged Linux image is required")
    runtime = BUILD / "appimage-tools/runtime-x86_64"
    runtime.parent.mkdir(parents=True, exist_ok=True)
    if not runtime.is_file() or hashlib.sha256(runtime.read_bytes()).hexdigest() != spec["sha256"]:
        request = urllib.request.Request(spec["url"], headers={"Accept": "application/octet-stream", "User-Agent": "NearbyIM-build"})
        with urllib.request.urlopen(request, timeout=60) as response:
            content = response.read(spec["bytes"] + 1)
        if len(content) != spec["bytes"] or hashlib.sha256(content).hexdigest() != spec["sha256"]:
            raise SystemExit("AppImage runtime size/SHA-256 mismatch")
        runtime.write_bytes(content)
    if runtime.read_bytes()[8:11] != b"AI\x02":
        raise SystemExit("Expected an official type-2 AppImage runtime")
    appdir = BUILD / "NearbyIM.AppDir"
    shutil.rmtree(appdir, ignore_errors=True)
    shutil.copytree(image, appdir / "usr", symlinks=True)
    (appdir / "AppRun").write_text('''#!/bin/sh
set -eu
if [ -z "${APPDIR:-}" ]; then APPDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd); fi
exec "$APPDIR/usr/bin/NearbyIM" "$@"
''')
    (appdir / "AppRun").chmod(0o755)
    (appdir / "nearbyim.desktop").write_text(f'''[Desktop Entry]
Type=Application
Name=NearbyIM
Name[zh_CN]=我在
Name[zh_TW]=我在
Exec=NearbyIM
Icon=nearbyim
Terminal=false
Categories=Network;InstantMessaging;
StartupNotify=true
StartupWMClass=dev-ghost-wozai-Main
X-AppImage-Version={version}
''')
    shutil.copyfile(DESKTOP / "assets/icons/linux/hicolor/256x256/apps/nearbyim.png", appdir / "nearbyim.png")
    (appdir / ".DirIcon").symlink_to("nearbyim.png")
    subprocess.run(["desktop-file-validate", str(appdir / "nearbyim.desktop")], check=True)
    epoch = subprocess.check_output(["git", "show", "-s", "--format=%ct", "HEAD"], cwd=ROOT, text=True).strip()
    payload = BUILD / "appimage-tools/payload.squashfs"
    payload.unlink(missing_ok=True)
    subprocess.run(["mksquashfs", str(appdir), str(payload), "-noappend", "-all-root", "-comp", "zstd",
                    "-Xcompression-level", "15", "-b", "1M", "-processors", "2", "-mkfs-time", epoch, "-all-time", epoch], check=True)
    output = BUILD / f"NearbyIM-{version}-linux-x64.AppImage"
    temporary = output.with_suffix(".AppImage.tmp")
    try:
        with temporary.open("wb") as destination:
            for source in (runtime, payload):
                with source.open("rb") as stream:
                    shutil.copyfileobj(stream, destination)
        temporary.chmod(0o755)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    offset = int(subprocess.check_output([str(output), "--appimage-offset"], text=True))
    with output.open("rb") as stream:
        stream.seek(offset)
        if stream.read(4) != b"hsqs":
            raise SystemExit("AppImage runtime does not locate its SquashFS payload")
    print(f"AppImage: {output.name}, runtime {spec['source_commit']}, {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
