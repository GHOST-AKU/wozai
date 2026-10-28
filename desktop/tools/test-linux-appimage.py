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


def locale_matrix(artifact, fixture, x11):
    cases = [
        ({'LANG':'zh_CN.UTF-8'},'system','zh-Hans'),
        ({'LANG':'en_US.UTF-8'},'system','en'),
        ({'LANG':'fr_FR.UTF-8'},'system','en'),
        ({'LANG':'zh_CN.UTF-8','LC_MESSAGES':'en_US.UTF-8'},'system','en'),
        ({'LANG':'en_US.UTF-8','LC_MESSAGES':'en_US.UTF-8','LC_ALL':'zh_CN.UTF-8'},'system','zh-Hans'),
        ({'LANG':'zh_CN.UTF-8','LC_MESSAGES':'zh_CN.UTF-8','LC_ALL':'C'},'system','en'),
        ({'LANG':'zh_CN.UTF-8','LC_MESSAGES':'','LC_ALL':''},'system','zh-Hans'),
        ({},'system','en'),
        ({'LC_ALL':'bad locale','LANG':'zh_CN.UTF-8'},'system','en'),
        ({'LANG':'zh_TW.UTF-8'},'system','zh-Hant'),
        ({'LANG':'ja_JP.UTF-8'},'system','ja'),
        ({'LANG':'ko_KR.UTF-8'},'system','ko'),
        ({'LANG':'zh_CN.UTF-8'},'en','en'),
        ({'LANG':'en_US.UTF-8'},'zh-Hans','zh-Hans')]
    results=[]
    for index,(inputs,selection,expected) in enumerate(cases):
        environment=os.environ.copy()
        for key in ['LC_ALL','LC_MESSAGES','LANG','LANGUAGE','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:
            environment.pop(key,None)
        environment.update(inputs)
        environment['APPIMAGE_EXTRACT_AND_RUN']='1'
        home=fixture/f'locale-{index}'
        environment['XDG_DATA_HOME']=str(home)
        profile=home/'wozai';profile.mkdir(parents=True,mode=0o700)
        (profile/'settings.properties').write_text('language='+selection+'\n',encoding='ascii')
        def matching_window():
            for window,title in smoke.windows():
                if title in ('我在','NearbyIM'):
                    metadata=subprocess.check_output(['xprop','-id',hex(window),'WM_CLASS'],text=True)
                    if 'dev-ghost-wozai-Main' in metadata:return window
        for attempt in range(2 if selection!='system' else 1):
            report=profile/'text-rendering.txt';report.unlink(missing_ok=True)
            with (BUILD/f'linux-appimage-i18n-{index}-{attempt}.txt').open('w') as log:
                process=subprocess.Popen([artifact,'--text-diagnostics'],env=environment,cwd=fixture,stdout=log,stderr=subprocess.STDOUT)
                active=None
                try:
                    active=smoke.await_condition(matching_window,'locale window missing',timeout=30)
                    smoke.await_condition(lambda:report.exists() and 'language.effective=' in report.read_text(), 'locale diagnostic missing',timeout=30)
                    actual=report.read_text()
                    assert 'language.effective='+expected+'\n' in actual,(inputs,selection,actual)
                    assert 'language.selection='+selection+'\n' in actual
                    before=(profile/'identity.properties').read_bytes()
                    if attempt:assert before==identity,'restart replaced identity'
                    identity=before
                    if index in (0,1,9):smoke.screenshot(active,BUILD/f'linux-appimage-i18n-{expected}.png')
                    x11.close_window(active);assert process.wait(timeout=20)==0;active=None
                finally:
                    if active:x11.close_window(active)
                    if process.poll() is None:process.terminate();process.wait(timeout=5)
        results.append({'input':inputs,'selection':selection,'effective':expected,'restart':selection!='system'})
    (BUILD/'linux-appimage-i18n-matrix.txt').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')


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
            locale_matrix(artifact, fixture, x11)
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
