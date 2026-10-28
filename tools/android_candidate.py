"""Prove a reused Android client's inputs and optimized code remain unchanged."""
import hashlib
import re
import subprocess
import zipfile


def unchanged(root, source):
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise SystemExit("Candidate source must be a full commit SHA")
    subprocess.run(["git", "merge-base", "--is-ancestor", source, "HEAD"], cwd=root, check=True)
    subprocess.run(["git", "diff", "--exit-code", source, "HEAD", "--",
                    "app/src/main", "app/build.gradle.kts", "app/proguard-instrumentation.pro",
                    "build.gradle.kts", "settings.gradle.kts", "gradle", "gradlew", "gradlew.bat",
                    "gradle.properties", "third_party", "i18n", "tools/generate-i18n.py",
                    "tools/check-app-icons.py"], cwd=root, check=True)


def matching_dex(root, candidate):
    with zipfile.ZipFile(candidate) as archive:
        expected = {name: hashlib.sha256(archive.read(name)).hexdigest()
                    for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)}
    directory = root / "app/build/intermediates/dex/preview/minifyPreviewWithR8"
    actual = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
              for path in directory.glob("classes*.dex")}
    if not expected or actual != expected:
        raise SystemExit("Rebuilt test mapping does not match the reused client's optimized DEX")
    print("Reused client DEX matches the test build exactly:", expected)
