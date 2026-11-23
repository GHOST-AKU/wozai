#!/usr/bin/env python3
"""Package the already built, unsigned iPhone/iPad application for candidate delivery."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import zipfile

from version import ROOT, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    args = parser.parse_args()
    app = args.app.resolve()
    version = metadata()
    info = plistlib.loads((app / "Info.plist").read_bytes())
    expected = {
        "CFBundleIdentifier": "dev.ghost.wozai.apple",
        "CFBundleExecutable": "NearbyIM",
        "CFBundleShortVersionString": version["base_version"],
        "CFBundleVersion": str(version["build_number"]),
        "WOZAI_VERSION": version["version"],
        "CFBundleSupportedPlatforms": ["iPhoneOS"],
    }
    for key, value in expected.items():
        if info.get(key) != value:
            raise SystemExit(f"Unexpected {key}: {info.get(key)!r}")
    if set(info.get("UIDeviceFamily", [])) != {1, 2}:
        raise SystemExit("Expected iPhone and iPad device families")
    if (app / "embedded.mobileprovision").exists():
        raise SystemExit("Expected an unsigned device build")
    for name in ("NearbyIM", "catalog.tsv.gz", "PrivacyInfo.xcprivacy"):
        if not (app / name).is_file():
            raise SystemExit(f"Required application resource missing: {name}")
    emoji_sha = hashlib.sha256((app / "catalog.tsv.gz").read_bytes()).hexdigest()
    if emoji_sha != hashlib.sha256((ROOT / "i18n/emoji/catalog.tsv.gz").read_bytes()).hexdigest():
        raise SystemExit("Bundled emoji catalog does not match the repository")

    output = ROOT / "build/apple-mobile-candidate"
    output.mkdir(parents=True, exist_ok=True)
    ipa = output / f"NearbyIM-{version['version']}-ios-ipados-unsigned.ipa"
    with zipfile.ZipFile(ipa, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as package:
        for member in sorted(app.rglob("*")):
            if member.is_file():
                package.write(member, f"Payload/NearbyIM.app/{member.relative_to(app).as_posix()}")
    with zipfile.ZipFile(ipa) as package:
        if package.testzip() is not None:
            raise SystemExit("Invalid package CRC")
        if not (package.getinfo("Payload/NearbyIM.app/NearbyIM").external_attr >> 16) & 0o111:
            raise SystemExit("Packaged binary lacks execute permission")
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    mesh_info = ROOT / "mesh/target/apple-native/build-info.json"
    mesh = json.loads(mesh_info.read_text())
    if mesh["source"] != source or not any("ios-arm64/" in library for library in mesh["libraries"]):
        raise SystemExit("iOS Mesh archive does not match the candidate build inputs")
    shutil.copyfile(mesh_info, output / "mesh-build.json")
    shutil.copyfile(ROOT / "LICENSE", output / "LICENSE.txt")
    shutil.copyfile(ROOT / "docs/releases/native-preview-install.md", output / "INSTALL.md")
    report = {"source_commit": source, "bundle": expected["CFBundleIdentifier"], "version": version["version"],
              "build": version["build_number"], "device_families": [1, 2], "platform": "iPhoneOS", "signed": False,
              "sha256": hashlib.sha256(ipa.read_bytes()).hexdigest(), "bytes": ipa.stat().st_size}
    (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
