#!/usr/bin/env python3
"""Compile the isolated JSONL adapter against byte-for-byte original Java sources."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "windows-next/build/bridge"
GSON = ("57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b",
        "com/google/code/gson/gson/2.11.0/gson-2.11.0.jar")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test", action="store_true", help="Run real-kernel JSONL integration tests after compiling")
    args = parser.parse_args()
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        parser.error("A full JDK 17 or newer is required; set JAVA_HOME")
    libraries = BUILD / "lib"
    libraries.mkdir(parents=True, exist_ok=True)
    dependencies = [tuple(line.split()) for line in (ROOT / "desktop/dependencies.txt").read_text().splitlines() if line.strip()]
    for digest, artifact in dependencies + [GSON]:
        destination = libraries / artifact.rsplit("/", 1)[1]
        if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == digest:
            continue
        cached = ROOT / "desktop/build/lib" / destination.name
        data = cached.read_bytes() if cached.exists() else b""
        if hashlib.sha256(data).hexdigest() != digest:
            with urllib.request.urlopen("https://maven-central.storage-download.googleapis.com/maven2/" + artifact, timeout=60) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != digest:
            raise SystemExit("Dependency checksum mismatch: " + artifact)
        destination.write_bytes(data)
    classes, tests = BUILD / "classes", BUILD / "tests"
    for output in (classes, tests):
        if output.exists():
            shutil.rmtree(output)
        output.mkdir(parents=True)
    sources = []
    for directory in ("desktop/src/main/java", "app/src/main/java/dev/ghost/nearbyim/core",
                      "app/src/main/java/dev/ghost/nearbyim/noise", "app/src/main/java/dev/ghost/nearbyim/verification",
                      "third_party/noise-java/src", "windows-next/bridge/src/main/java"):
        sources.extend(sorted((ROOT / directory).rglob("*.java")))
    sources.extend(path for path in sorted((ROOT / "app/src/main/java/dev/ghost/nearbyim/i18n").rglob("*.java")) if path.name != "I18nResources.java")
    sources.append(ROOT / "app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java")
    # javac argument files also work when the checkout path contains spaces.
    source_file = BUILD / "sources.txt"
    source_file.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
    subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", str(libraries / "*"), "-d", str(classes), "@" + str(source_file)], check=True)
    shutil.copytree(ROOT / "desktop/src/main/resources", classes, dirs_exist_ok=True)
    test_sources = sorted((ROOT / "windows-next/bridge/src/test/java").rglob("*.java"))
    test_source_file = BUILD / "test-sources.txt"
    test_source_file.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in test_sources), encoding="utf-8")
    classpath = os.pathsep.join((str(classes), str(tests), str(libraries / "*")))
    if test_sources:
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(tests), "@" + str(test_source_file)], check=True)
    print("Bridge classes: " + str(classes), flush=True)
    print("Bridge dependencies: " + str(libraries), flush=True)
    print("Bridge tests: " + str(tests), flush=True)
    if args.test:
        subprocess.run([java, "-Djava.awt.headless=true", "-cp", classpath, "dev.ghost.wozai.next.BridgeIntegrationTests"], check=True)


if __name__ == "__main__":
    main()
