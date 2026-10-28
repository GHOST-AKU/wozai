#!/usr/bin/env python3
"""Exercise the actual AppImage, including its no-FUSE path and menu restart."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location("package_smoke", Path(__file__).with_name("test-linux-package.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "desktop/build"


def main():
    version = json.loads((ROOT / "i18n/config.json").read_text())["appVersion"]
    original = BUILD / f"NearbyIM-{version}-linux-x64.AppImage"
    expected = hashlib.sha256(original.read_bytes()).hexdigest()
    x11 = smoke.X11()
    active = None
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="nearbyim-appimage-") as temporary:
            fixture = Path(temporary)
            artifact = fixture / 'NearbyIM O\'Brien $HOME %f "中文" \\ release.AppImage'
            shutil.copyfile(original, artifact)
            artifact.chmod(0o555)
            with (BUILD / "linux-appimage-extract.txt").open("w") as log:
                subprocess.run([artifact, "--appimage-extract"], cwd=fixture, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=45)
            extracted = fixture / "squashfs-root"
            assert (extracted / "AppRun").is_file() and (extracted / ".DirIcon").is_file()
            subprocess.run(["desktop-file-validate", extracted / "nearbyim.desktop"], check=True)
            with zipfile.ZipFile(extracted / "usr/lib/app/nearbyim-desktop.jar") as jar:
                build = json.loads(jar.read("dev/ghost/wozai/build-info.json"))
            head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
            assert build["version"] == version and build["source_commit"] == head and build["working_tree_dirty"] is False
            assert (extracted / "usr/licenses/AppImage-runtime-LICENSE.txt").is_file()
            assert (extracted / "usr/lib/runtime/legal/java.base/LICENSE").is_file()
            assert (extracted / "usr/lib/app/libwozai_bluetooth.so").is_file()
            shutil.copyfile(extracted / "usr/runtime-requirements.txt", BUILD / "linux-appimage-runtime-requirements.txt")
            # Reuse the production encrypted LAN/consent/storage regression with
            # the extracted AppImage's JVM and JARs, never the build-host JDK.
            runtime = extracted / "usr/lib/runtime/bin/java"
            with (BUILD / "linux-appimage-messaging.txt").open("w") as log:
                subprocess.run([runtime, "-Dwozai.bluetooth.library=" + str(extracted / "usr/lib/app/libwozai_bluetooth.so"),
                                "-cp", str(extracted / "usr/lib/app/*") + os.pathsep + str(BUILD / "tests"),
                                "dev.ghost.wozai.DiscoveryTests"],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
            shutil.rmtree(extracted)
            environment = os.environ.copy()
            environment["APPIMAGE_EXTRACT_AND_RUN"] = "1"
            environment["XDG_DATA_HOME"] = str(fixture / "user-data")
            environment["JAVA_TOOL_OPTIONS"] = '-Duser.home="' + str(fixture / "user-home") + '" -Duser.language=zh -Duser.country=CN'
            profile = Path(environment["XDG_DATA_HOME"]) / "wozai"
            with (BUILD / "linux-appimage-launch.txt").open("w") as log:
                process = subprocess.Popen([artifact, "--text-diagnostics"], cwd=fixture, env=environment, stdout=log, stderr=subprocess.STDOUT)
                active = smoke.await_condition(smoke.chat_window, "AppImage did not display its native window", timeout=30)
                identity = profile / "identity.properties"
                smoke.await_condition(identity.is_file, "AppImage did not create an XDG identity")
                before = identity.read_bytes()
                assert stat.S_IMODE(profile.stat().st_mode) == 0o700
                assert stat.S_IMODE(identity.stat().st_mode) == 0o600
                report = profile / "text-rendering.txt"
                smoke.await_condition(report.is_file, "AppImage bundled runtime did not report CJK font rendering")
                assert "Noto Sans CJK SC" in report.read_text()
                shutil.copyfile(report, BUILD / "linux-appimage-text-rendering.txt")
                width, height = smoke.screenshot(active, BUILD / "linux-appimage-window.png")
                assert width >= 760 and height >= 540
                x11.close_window(active)
                assert process.wait(timeout=20) == 0
                active = None
            registration = subprocess.run([artifact, "--install-desktop"], cwd=fixture, env=environment, capture_output=True, text=True, timeout=30)
            assert registration.returncode == 0, registration.stdout + registration.stderr
            entry = Path(environment["XDG_DATA_HOME"]) / "applications/nearbyim.desktop"
            subprocess.run(["desktop-file-validate", entry], check=True)
            text = entry.read_text()
            assert "release.AppImage" in text and ".mount_" not in text and "appimage_extracted_" not in text
            assert Path(environment["XDG_DATA_HOME"], "icons/hicolor/256x256/apps/nearbyim.png").is_file()
            smoke.launch_desktop(entry, environment)
            active = smoke.await_condition(smoke.chat_window, "AppImage menu entry did not restart the artifact", timeout=30)
            assert identity.read_bytes() == before, "AppImage menu restart replaced the device identity"
            x11.close_window(active)
            smoke.await_condition(lambda: active not in [window for window, _ in smoke.windows()], "AppImage menu window did not exit")
            active = None
            assert hashlib.sha256(artifact.read_bytes()).hexdigest() == expected
            assert not list(fixture.glob("*.AppImage.home")) and not (fixture / "data").exists()
            print("AppImage smoke: type-2 extraction, exact source/version, bundled-runtime NIM4 LAN consent/messages/saved receipts, no-FUSE GUI, bundled JVM/CJK/JNI/licenses, read-only special path, private XDG identity, persistent menu/icon, GIO restart and clean exit passed")
    finally:
        if active:
            x11.close_window(active)
        if process and process.poll() is None:
            process.terminate()
            process.wait(timeout=5)
        x11.close()


if __name__ == "__main__":
    main()
