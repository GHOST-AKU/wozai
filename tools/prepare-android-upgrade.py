#!/usr/bin/env python3
"""Prepare public APK-only inputs, or verify input hashes before installing them."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
pin = json.loads((root / "tools/android-signing.json").read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("mode", choices=["prepare", "verify"])
parser.add_argument("--baseline-code", type=int, choices=[7, 12], default=7)
args = parser.parse_args()
folder = root / "build/signing-upgrade"
source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, cwd=root).strip()
maintenance_source = "c0855151a2012954f1b34a88eb9b8ca0b4c8355a"
names = {"candidate.apk", "test.apk", "baseline.apk", "maintenance.apk", "wrong.apk"}
if args.baseline_code == 12:
    names = {"candidate.apk", "test.apk", "published-v032.apk", "wrong.apk"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


if args.mode == "prepare":
    if args.baseline_code == 12 and not (folder / "published-v032.apk").exists():
        baseline = pin["published_upgrade_baselines"]["v0.3.2"]
        subprocess.run(["gh", "release", "download", baseline["tag"], "--repo", "GHOST-AKU/wozai",
                        "--pattern", baseline["asset"], "--dir", str(folder)], check=True)
        (folder / baseline["asset"]).rename(folder / "published-v032.apk")
    if args.baseline_code == 7 and not (folder / "baseline.apk").exists():
        subprocess.run(["gh", "release", "download", pin["baseline_tag"], "--repo", "GHOST-AKU/wozai",
                        "--pattern", pin["baseline_apk"], "--dir", str(folder)], check=True)
        (folder / pin["baseline_apk"]).rename(folder / "baseline.apk")
    info = {"source_commit": source, "baseline_code": args.baseline_code, "assets": {name: digest(folder / name) for name in sorted(names)}}
    (folder / "input-manifest.json").write_text(json.dumps(info, indent=2) + "\n")
else:
    info = json.loads((folder / "input-manifest.json").read_text())
    if info["source_commit"] != source or set(info["assets"]) != names or info.get("baseline_code", 7) != args.baseline_code:
        raise SystemExit("Upgrade inputs do not match this source revision")
    for name, expected in info["assets"].items():
        if digest(folder / name) != expected:
            raise SystemExit("Upgrade input digest mismatch: " + name)
checks = [("baseline.apk", ["--baseline"]), ("candidate.apk", ["--source-commit", source]),
                    ("maintenance.apk", ["--maintenance", "--source-commit", maintenance_source]),
                    ("test.apk", ["--test-apk"]), ("wrong.apk", ["--wrong-signer"])]
if args.baseline_code == 12:
    if digest(folder / "wrong.apk") != "041683734135c4bf8928c0f9edb7720ea4737d83b343ef6512fd1c6da29ad341":
        raise SystemExit("Wrong-signer fixture differs from the withdrawn f08e8ba APK")
    checks = [("published-v032.apk", ["--published-baseline", "v0.3.2"]),
              ("candidate.apk", ["--source-commit", source]), ("test.apk", ["--test-apk"]),
              ("wrong.apk", ["--wrong-signer"])]
for name, flags in checks:
    subprocess.run(["python3", str(root / "tools/verify-android-signature.py"), str(folder / name), *flags], check=True)
print("Upgrade inputs verified; published baseline and candidate provenance match")
