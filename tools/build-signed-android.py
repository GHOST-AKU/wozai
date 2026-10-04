#!/usr/bin/env python3
"""Build using CI secrets or a private local keystore; never expose key material."""
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
pin = json.loads((root / "tools/android-signing.json").read_text())
password = os.environ.get("WOZAI_ANDROID_KEYSTORE_PASSWORD")
encoded = os.environ.get("WOZAI_ANDROID_KEYSTORE_BASE64")
local = os.environ.get("WOZAI_ANDROID_KEYSTORE")
if not password or bool(encoded) == bool(local):
    raise SystemExit("Configure WOZAI_ANDROID_KEYSTORE_BASE64 and WOZAI_ANDROID_KEYSTORE_PASSWORD in repository Secrets, or supply a private local WOZAI_ANDROID_KEYSTORE path and password")
code = int(re.search(r"versionCode\s*=\s*(\d+)", (root / "app/build.gradle.kts").read_text()).group(1))
if code <= pin["baseline_version_code"]:
    raise SystemExit("Increment versionCode above the published baseline before building an upgrade")
source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
if subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip():
    raise SystemExit("Signed release builds require a clean source tree")
with tempfile.TemporaryDirectory(prefix="wozai-signing-", dir=os.environ.get("RUNNER_TEMP")) as private:
    key = Path(private) / "signing.p12"
    if encoded:
        try:
            key.write_bytes(base64.b64decode(encoded, validate=True))
        except ValueError:
            raise SystemExit("Signing key secret is not valid base64") from None
    else:
        shutil.copyfile(local, key)
    key.chmod(0o600)
    environment = os.environ.copy()
    environment.pop("WOZAI_ANDROID_KEYSTORE_BASE64", None)
    environment["WOZAI_ANDROID_KEYSTORE"] = str(key)
    # Isolate the intentionally different debug signer used by the rejection test.
    environment["ANDROID_USER_HOME"] = str(Path(private) / "android-user")
    keytool = Path(os.environ["JAVA_HOME"]) / "bin/keytool"
    certificate = subprocess.run([str(keytool), "-list", "-v", "-keystore", str(key),
                                  "-storepass:env", "WOZAI_ANDROID_KEYSTORE_PASSWORD", "-alias", pin["key_alias"]],
                                 env=environment, capture_output=True, text=True)
    fingerprint = re.search(r"SHA256:\s*([0-9A-F:]+)", certificate.stdout)
    if certificate.returncode or not fingerprint or fingerprint.group(1).replace(":", "").lower() != pin["certificate_sha256"]:
        raise SystemExit("Private keystore/password/alias does not match the persistent certificate; refusing to build")
    subprocess.run([str(root / "gradlew"), "--no-daemon", "-PtestBuildType=release",
                    ":app:assembleRelease", ":app:assembleReleaseAndroidTest", ":app:assembleDebug", ":app:lintRelease"],
                   cwd=root, env=environment, check=True)
    folder = root / "build/signing-upgrade"
    folder.mkdir(parents=True, exist_ok=True)
    files = [("app/build/outputs/apk/release/app-release.apk", "candidate.apk", []),
             ("app/build/outputs/apk/androidTest/release/app-release-androidTest.apk", "test.apk", ["--test-apk"]),
             ("app/build/outputs/apk/debug/app-debug.apk", "wrong.apk", ["--wrong-signer"])]
    for original, name, flags in files:
        shutil.copyfile(root / original, folder / name)
        if name == "candidate.apk":
            flags += ["--source-commit", source, "--output", str(folder / "signature.json")]
        subprocess.run(["python3", str(root / "tools/verify-android-signature.py"), str(folder / name), *flags],
                       cwd=root, env=environment, check=True)
print("Signed build complete; temporary keystore and wrong-signer development key removed")
