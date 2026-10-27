#!/usr/bin/env python3
"""Validate build provenance and stage assets in an existing draft, never publish it."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def metadata(jar, version, source):
    with zipfile.ZipFile(jar) as archive:
        info = json.loads(archive.read("dev/ghost/wozai/build-info.json"))
    require(info["version"] == version, "Wrong package version")
    require(info["source_commit"] == source, "Wrong package source commit")
    require(info["working_tree_dirty"] is False, "Package built from a dirty tree")


def desktop(version, source, tag):
    inputs = Path("build/release-input")
    output = Path("build/release-assets")
    output.mkdir(parents=True, exist_ok=True)
    image = inputs / "windows/NearbyIM"
    require(image.is_dir(), "Missing Windows application image")
    require(not (image / "data").exists(), "Windows package contains a user profile")
    require((image / "NearbyIM.exe").is_file(), "Missing Windows executable")
    metadata(image / "app/nearbyim-desktop.jar", version, source)
    windows = output / f"NearbyIM-{version}-windows-x64.zip"
    with zipfile.ZipFile(windows, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(image.rglob("*")):
            require(not path.is_symlink(), "Unexpected Windows symlink")
            if path.is_file():
                archive.write(path, path.relative_to(image.parent))
    files = [windows]
    linux = inputs / "linux"
    for suffix in ("tar.gz", "deb", "AppImage"):
        name = f"NearbyIM-{version}-linux-x64.{suffix}"
        path = linux / name
        require(path.is_file(), f"Missing {name}")
        if suffix == "tar.gz":
            with tarfile.open(path) as archive:
                candidates = [m for m in archive.getmembers()
                              if m.name.endswith("/lib/app/nearbyim-desktop.jar")]
                require(len(candidates) == 1, "Ambiguous Linux application JAR")
                metadata(io.BytesIO(archive.extractfile(candidates[0]).read()), version, source)
        elif suffix == "deb":
            deb_version = version.replace("-preview.", "~preview.")
            require(run("dpkg-deb", "-f", str(path), "Version") == deb_version,
                    "Wrong Debian package version")
            extracted = output / "deb-inspection"
            subprocess.run(["dpkg-deb", "-x", str(path), str(extracted)], check=True)
            jars = list(extracted.rglob("nearbyim-desktop.jar"))
            require(len(jars) == 1, "Ambiguous Debian application JAR")
            metadata(jars[0], version, source)
            shutil.rmtree(extracted)
        else:
            runtime = json.loads(Path("desktop/appimage-runtime.json").read_text())
            with path.open("rb") as stream:
                require(hashlib.sha256(stream.read(runtime["bytes"])).hexdigest() == runtime["sha256"],
                        "Wrong AppImage runtime")
            jar = subprocess.check_output(["unsquashfs", "-cat", "-o", str(runtime["bytes"]), str(path),
                                           "usr/lib/app/nearbyim-desktop.jar"])
            metadata(io.BytesIO(jar), version, source)
        target = output / name
        shutil.copyfile(path, target)
        files.append(target)
    manifest = {"version": version, "source_commit": source,
                "workflow_run": os.environ["GITHUB_RUN_ID"],
                "assets": [{"name": p.name, "bytes": p.stat().st_size,
                            "sha256": digest(p)} for p in files]}
    manifest_path = output / "BUILD-MANIFEST-DESKTOP.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    subprocess.run(["gh", "release", "upload", tag, "--clobber",
                    *map(str, files), str(manifest_path)], check=True)
    print("RELEASE_DESKTOP_MANIFEST: " + json.dumps(manifest))


def preview(version, source, tag):
    require("-preview." in tag, "Combined preview staging requires a preview release")
    android_run = os.environ["SIGNED_ANDROID_RUN_ID"]
    require(android_run.isdecimal(), "Invalid Android workflow run")
    repo = os.environ["GH_REPO"]
    build = json.loads(run("gh", "api", f"repos/{repo}/actions/runs/{android_run}"))
    require(build["head_sha"] == source and build["conclusion"] == "success", "Android build source/result mismatch")
    output = Path("build/release-assets")
    desktop_info = json.loads((output / "BUILD-MANIFEST-DESKTOP.json").read_text())
    require(desktop_info["source_commit"] == source, "Desktop build source mismatch")
    apk = output / f"NearbyIM-{version}-android.apk"
    shutil.copyfile("build/release-input/android-signed/candidate.apk", apk)
    signature = output / "ANDROID-SIGNATURE.json"
    subprocess.run([sys.executable, "tools/verify-android-signature.py", str(apk),
                    "--source-commit", source, "--output", str(signature)], check=True)
    runtime_report = output / "LINUX-RUNTIME-REQUIREMENTS.txt"
    with tarfile.open(f"build/release-input/linux/NearbyIM-{version}-linux-x64.tar.gz") as archive:
        runtime_report.write_bytes(archive.extractfile("NearbyIM/runtime-requirements.txt").read())
    blockers = Path("build/release-input/windows/PREVIEW-ACCEPTANCE.txt")
    failures = blockers.read_text().splitlines() if blockers.is_file() else []
    base = f"https://github.com/{repo}"
    desktop_run = os.environ["GITHUB_RUN_ID"]
    body = json.loads(run("gh", "release", "view", tag, "--json", "body"))["body"].split("## 来源与核验", 1)[0]
    body += f"""## 来源与核验

发布源提交：`{source}`。五个客户端发行文件均来自此提交的干净构建。

- [Windows/Linux/AppImage构建与实际包验证]({base}/actions/runs/{desktop_run})；仅明确preview构建将500ms文字回执性能门槛记为预览警告，正确性/重叠数/超时/完整性仍严格检查，普通CI默认仍失败。
- [Android固定签名release/lint]({base}/actions/runs/{android_run})；最终APK再次核验来源、固定证书、版本、对齐与不可调试标记。
- Android产品源码与[1782472的92项API34自适应/真实Noise文件/5秒期限检查]({base}/actions/runs/38734231356)相同；本轮没有重跑多API升级矩阵或真机无线验收。
- 首轮严格Windows检查[P95=510.847299ms、500ms门槛失败]({base}/actions/runs/38756908082)保留，不是正式性能验收通过。当前预览构建告警：{'; '.join(failures) if failures else '本轮未触发额外P95告警；历史失败仍保留'}。
- Android固定证书SHA-256：`{json.loads(signature.read_text())['certificate_sha256']}`。匹配证书时可覆盖更新，保留聊天数据；不要用卸载规避签名错误。
- `SHA256SUMS.txt`核对所有发布文件，`BUILD-MANIFEST.json`记录源提交、构建任务、字节数/哈希及预览质量限制，`ANDROID-SIGNATURE.json`记录APK核验。

发布与原始证据：[Issue #26]({base}/issues/26)。真实P2P失败、UI问题、蓝牙/内存等限制继续开放。
"""
    notes = output / "RELEASE-NOTES.md"
    notes.write_text(body)
    files = [output / item["name"] for item in desktop_info["assets"]]
    files += [output / "BUILD-MANIFEST-DESKTOP.json", apk, signature, runtime_report, notes]
    manifest = {"release_tag": tag, "version": version, "source_commit": source,
                "desktop_workflow_run": desktop_run, "android_workflow_run": android_run,
                "preview_performance_warnings": failures,
                "formal_performance_acceptance": False,
                "known_limits": ["temporary_p2p_failed_on_user_devices", "tablet_foldable_ui_rough_edges",
                                 "physical_bluetooth_unverified", "idle_connected_memory_unresolved"],
                "appimage_runtime": json.loads(Path("desktop/appimage-runtime.json").read_text()),
                "assets": [{"name": p.name, "bytes": p.stat().st_size, "sha256": digest(p)} for p in files]}
    unified = output / "BUILD-MANIFEST.json"
    unified.write_text(json.dumps(manifest, indent=2) + "\n")
    files.append(unified)
    checksums = output / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{digest(p)}  {p.name}\n" for p in sorted(files)))
    subprocess.run(["gh", "release", "edit", tag, "--notes-file", str(notes)], check=True)
    subprocess.run(["gh", "release", "upload", tag, "--clobber", str(apk), str(signature),
                    str(runtime_report), str(notes), str(unified), str(checksums)], check=True)
    print("PREVIEW_RELEASE_MANIFEST: " + json.dumps(manifest))


def android(version, source, tag):
    folder = Path("build/release-input/android")
    folder.mkdir(parents=True, exist_ok=True)
    app_name = f"NearbyIM-{version}-android.apk"
    test_name = f"NearbyIM-{version}-android-test.apk"
    subprocess.run(["gh", "release", "download", tag, "--dir", str(folder),
                    "--pattern", app_name, "--pattern", test_name,
                    "--pattern", "ANDROID-INPUT-MANIFEST.json"], check=True)
    info = json.loads((folder / "ANDROID-INPUT-MANIFEST.json").read_text())
    require(info["version"] == version and info["source_commit"] == source,
            "Wrong Android release provenance")
    expected_code = int(re.search(r"versionCode\s*=\s*(\d+)",
                                  Path("app/build.gradle.kts").read_text()).group(1))
    require(info["version_code"] == expected_code, "Wrong Android input version code")
    require(set(info["assets"]) == {app_name, test_name}, "Unexpected Android inputs")
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    for name, target in [(app_name, "preview/app-preview.apk"),
                         (test_name, "androidTest/preview/app-preview-androidTest.apk")]:
        apk = folder / name
        require(digest(apk) == info["assets"][name], "APK digest mismatch")
        signature = run(str(sdk / "apksigner"), "verify", "--print-certs", str(apk))
        cert = re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", signature)
        require(cert and cert.group(1) == info["certificate_sha256"], "APK certificate mismatch")
        subprocess.run([str(sdk / "zipalign"), "-c", "4", str(apk)], check=True)
        if name == app_name:
            badging = run(str(sdk / "aapt2"), "dump", "badging", str(apk))
            package = badging.splitlines()[0]
            require("name='dev.ghost.nearbyim'" in package, "Wrong Android package")
            require(f"versionName='{version}'" in package, "Wrong Android version")
            require(f"versionCode='{info['version_code']}'" in package, "Wrong Android version code")
            require("application-debuggable" not in badging, "Release APK is debuggable")
        destination = Path("app/build/outputs/apk") / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, destination)
    print("Validated exact signed release APKs: " + json.dumps(info))


def main():
    version = json.loads(Path("i18n/config.json").read_text())["appVersion"]
    source = os.environ["GITHUB_SHA"]
    tag = os.environ["RELEASE_TAG"]
    require(tag == "v" + version,
            "Release tag does not match the shared version")
    require(run("git", "rev-parse", "HEAD") == source, "Unexpected checkout")
    release = json.loads(run("gh", "release", "view", tag, "--json", "isDraft,targetCommitish"))
    require(release["isDraft"] is True, "Assets may only be staged in a draft release")
    require(release["targetCommitish"] == source, "Draft points at another source commit")
    {"desktop": desktop, "android": android, "preview": preview}[sys.argv[1]](version, source, tag)


if __name__ == "__main__":
    main()
