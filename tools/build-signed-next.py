#!/usr/bin/env python3
"""Build Android Next with the existing persistent certificate, confined to CI."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
if not os.environ.get("WOZAI_ANDROID_KEYSTORE_BASE64") or not os.environ.get("WOZAI_ANDROID_KEYSTORE_PASSWORD"):
    raise SystemExit("Persistent signing secrets are unavailable")
if subprocess.check_output(["git", "status", "--porcelain"], cwd=root).strip():
    raise SystemExit("Signed builds require a clean source tree")
source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
output = root / "build/android-next-signed"
output.mkdir(parents=True, exist_ok=True)
version = json.loads((root / "i18n/config.json").read_text())["appVersion"]
with tempfile.TemporaryDirectory(prefix="next-signing-", dir=os.environ.get("RUNNER_TEMP")) as private:
    key = Path(private) / "signing.p12"
    key.write_bytes(base64.b64decode(os.environ["WOZAI_ANDROID_KEYSTORE_BASE64"], validate=True))
    key.chmod(0o600)
    env = os.environ.copy()
    env.pop("WOZAI_ANDROID_KEYSTORE_BASE64", None)
    env["WOZAI_ANDROID_KEYSTORE"] = str(key)
    certificate = subprocess.check_output([str(Path(env["JAVA_HOME"]) / "bin/keytool"), "-list", "-v", "-keystore", str(key),
        "-storepass:env", "WOZAI_ANDROID_KEYSTORE_PASSWORD", "-alias", "nearbyim"], env=env, text=True)
    pin = json.loads((root / "tools/android-signing.json").read_text())["certificate_sha256"]
    if pin not in certificate.replace(":", "").lower():
        raise SystemExit("Signing certificate does not match persistent pin")
    subprocess.run([str(root / "gradlew"), "--no-daemon", ":app-next:assembleRelease", ":app-next:lintRelease"], cwd=root, env=env, check=True)
    apk = output / f"WoZai-next-{version}-android.apk"
    shutil.copyfile(root / "app-next/build/outputs/apk/release/app-next-release.apk", apk)
    subprocess.run(["python3", str(root / "tools/verify-android-signature.py"), str(apk), "--next", "--source-commit", source,
        "--output", str(output / "signature.json")], cwd=root, env=env, check=True)
