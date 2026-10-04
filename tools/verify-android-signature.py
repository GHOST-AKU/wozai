#!/usr/bin/env python3
"""Reject unsigned, differently signed, wrong-version or debuggable release APKs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
pin = json.loads((root / "tools/android-signing.json").read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
mode = parser.add_mutually_exclusive_group()
mode.add_argument("--test-apk", action="store_true")
mode.add_argument("--baseline", action="store_true")
mode.add_argument("--wrong-signer", action="store_true")
parser.add_argument("--source-commit")
parser.add_argument("--output", type=Path)
args = parser.parse_args()
sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
signature = subprocess.check_output([str(sdk / "apksigner"), "verify", "--print-certs", str(args.apk)], text=True)
certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: (\w+)", signature)
if len(certificates) != 1 or ((certificates[0] == pin["certificate_sha256"]) == args.wrong_signer):
    raise SystemExit("APK signing certificate does not match the persistent signing pin")
subprocess.run([str(sdk / "zipalign"), "-c", "4", str(args.apk)], check=True)
with args.apk.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
info = {"apk": args.apk.name, "sha256": digest, "certificate_sha256": certificates[0]}
if not args.test_apk:
    badging = subprocess.check_output([str(sdk / "aapt2"), "dump", "badging", str(args.apk)], text=True)
    package = badging.splitlines()[0]
    version = pin["baseline_version_name"] if args.baseline else json.loads((root / "i18n/config.json").read_text())["appVersion"]
    code = pin["baseline_version_code"] if args.baseline else int(re.search(
        r"versionCode\s*=\s*(\d+)", (root / "app/build.gradle.kts").read_text()).group(1))
    if (f"name='{pin['application_id']}'" not in package or f"versionCode='{code}'" not in package
            or f"versionName='{version}'" not in package or (not args.wrong_signer and "application-debuggable" in badging)):
        raise SystemExit("Wrong APK package/version or debuggable release")
    if args.baseline and digest != pin["baseline_apk_sha256"]:
        raise SystemExit("Baseline does not match the published 0.3.1 APK")
    if args.source_commit:
        with zipfile.ZipFile(args.apk) as archive:
            metadata = archive.read("META-INF/version-control-info.textproto").decode()
        if f'revision: "{args.source_commit}"' not in metadata:
            raise SystemExit("APK source revision does not match its provenance")
        info["source_commit"] = args.source_commit
    info.update(version=version, version_code=code, debuggable="application-debuggable" in badging)
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(info, indent=2) + "\n")
print(json.dumps(info))
