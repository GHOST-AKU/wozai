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
    for suffix in ("tar.gz", "deb"):
        name = f"NearbyIM-{version}-linux-x64.{suffix}"
        path = linux / name
        require(path.is_file(), f"Missing {name}")
        if suffix == "tar.gz":
            with tarfile.open(path) as archive:
                candidates = [m for m in archive.getmembers()
                              if m.name.endswith("/lib/app/nearbyim-desktop.jar")]
                require(len(candidates) == 1, "Ambiguous Linux application JAR")
                metadata(io.BytesIO(archive.extractfile(candidates[0]).read()), version, source)
        else:
            require(run("dpkg-deb", "-f", str(path), "Version") == version,
                    "Wrong Debian package version")
            extracted = output / "deb-inspection"
            subprocess.run(["dpkg-deb", "-x", str(path), str(extracted)], check=True)
            jars = list(extracted.rglob("nearbyim-desktop.jar"))
            require(len(jars) == 1, "Ambiguous Debian application JAR")
            metadata(jars[0], version, source)
            shutil.rmtree(extracted)
        target = output / name
        shutil.copyfile(path, target)
        files.append(target)
    manifest = {"version": version, "source_commit": source,
                "workflow_run": os.environ["GITHUB_RUN_ID"],
                "assets": [{"name": p.name, "bytes": p.stat().st_size,
                            "sha256": digest(p)} for p in files]}
    manifest_path = output / "BUILD-MANIFEST-DESKTOP.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    subprocess.run(["gh", "release", "upload", tag,
                    *map(str, files), str(manifest_path)], check=True)
    print("RELEASE_DESKTOP_MANIFEST: " + json.dumps(manifest))


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
    require(tag == "v" + version, "Release tag does not match the shared version")
    require(run("git", "rev-parse", "HEAD") == source, "Unexpected checkout")
    release = json.loads(run("gh", "api", f"repos/{os.environ['GH_REPO']}/releases/tags/{tag}"))
    require(release["draft"] is True, "Assets may only be staged in a draft release")
    require(release["target_commitish"] == source, "Draft points at another source commit")
    {"desktop": desktop, "android": android}[sys.argv[1]](version, source, tag)


if __name__ == "__main__":
    main()
