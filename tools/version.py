#!/usr/bin/env python3
"""Synchronize platform versions from i18n/config.json without building clients."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def metadata(root=ROOT):
    config = json.loads((root / "i18n/config.json").read_text(encoding="utf-8"))
    version = config["appVersion"]
    match = re.fullmatch(r"((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"
                         r"(?:-preview\.([1-9][0-9]*))?", version)
    if not match:
        raise ValueError("appVersion must be X.Y.Z or X.Y.Z-preview.N (N >= 1)")
    build = config["buildNumber"]
    if type(build) is not int or not 1 <= build <= 2100000000:
        raise ValueError("buildNumber must be a positive Android-compatible integer")
    return {"version": version, "base_version": match[1], "build_number": build,
            "tag": "v" + version, "channel": "preview" if match[2] else "stable",
            "debian_version": version.replace("-preview.", "~preview.")}


def replace(text, pattern, replacement, count):
    result, found = re.subn(pattern, lambda _: replacement, text)
    if found != count:
        raise ValueError(f"Expected {count} version fields for {pattern!r}, found {found}")
    return result


def synchronize(root=ROOT, check=False):
    info = metadata(root)
    sources = ["app/build.gradle.kts", "terminal/Cargo.toml", "terminal/Cargo.lock",
               "apple/NearbyIM.xcodeproj/project.pbxproj"]
    changed = []
    for relative in sources:
        path = root / relative
        before = path.read_text(encoding="utf-8")
        after = before
        if relative.endswith("build.gradle.kts"):
            after = replace(after, r"versionCode\s*=\s*[0-9]+",
                            f"versionCode = {info['build_number']}", 1)
        elif relative.endswith("Cargo.toml"):
            after = replace(after, r'(?m)^version = "[^"\n]+"',
                            f'version = "{info["version"]}"', 1)
        elif relative.endswith("Cargo.lock"):
            after = replace(after, r'name = "nearbyim-terminal"\nversion = "[^"\n]+"',
                            f'name = "nearbyim-terminal"\nversion = "{info["version"]}"', 1)
        else:
            after = replace(after, r'MARKETING_VERSION = [^;]+;',
                            f'MARKETING_VERSION = {info["base_version"]};', 2)
            after = replace(after, r'CURRENT_PROJECT_VERSION = [^;]+;(?: WOZAI_VERSION = [^;]+;)?',
                            f'CURRENT_PROJECT_VERSION = {info["build_number"]}; '
                            f'WOZAI_VERSION = "{info["version"]}";', 2)
        if after != before:
            changed.append(relative)
            if not check:
                path.write_text(after, encoding="utf-8")
    if check and changed:
        raise ValueError("Version drift: run python3 tools/version.py --write: " + ", ".join(changed))
    return info, changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="update checked-in platform versions")
    mode.add_argument("--check", action="store_true", help="reject platform version drift")
    parser.add_argument("--field", choices=metadata().keys(), help="print one packaging value")
    args = parser.parse_args()
    if args.write or args.check:
        info, changed = synchronize(check=args.check)
    else:
        info, changed = metadata(), []
    if args.field:
        print(info[args.field])
    else:
        print(json.dumps({**info, "updated": changed}))


if __name__ == "__main__":
    main()
