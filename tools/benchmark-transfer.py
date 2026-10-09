#!/usr/bin/env python3
"""Run verified loopback measurements. Never report them as phone acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["raw-tcp", "signed-v3", "file-v2", "noise-v4"], default="signed-v3")
    parser.add_argument("--size", type=int, default=48 * 1024 * 1024, help="payload bytes; large runs require explicit size")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--output", type=Path, default=ROOT / "build/transfer-benchmark.json")
    parser.add_argument("--environment-kind", choices=["virtual"], default="virtual")
    parser.add_argument("--work-dir", type=Path, help="data directory; default system temporary directory may be tmpfs")
    args = parser.parse_args()
    classes = ROOT / "build/core-tests"
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "app/src/main/java/dev/ghost/nearbyim/core").glob("*.java"))
    sources += sorted((ROOT / "app/src/main/java/dev/ghost/nearbyim/noise").glob("*.java"))
    sources += sorted((ROOT / "third_party/noise-java/src").rglob("*.java"))
    sources += [ROOT / "app/src/main/java/dev/ghost/nearbyim/i18n" / name for name in ["UiText.java", "LocalizedIllegalArgumentException.java"]]
    sources += sorted((ROOT / "tests").glob("*.java"))
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)], check=True, cwd=ROOT)
    artifact = hashlib.sha256()
    for path in sorted(classes.rglob("*.class")):
        artifact.update(path.relative_to(classes).as_posix().encode() + b"\0")
        artifact.update(path.read_bytes())
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    command = ["java", "-Xmx32m", "-cp", str(classes), "dev.ghost.nearbyim.core.AttachmentTransferBenchmark",
                    "--mode", args.mode, "--size", str(args.size), "--rounds", str(args.rounds), "--output", str(args.output.resolve()),
                    "--environment-kind", args.environment_kind, "--source-commit", commit + ("+dirty" if dirty else ""),
                    "--artifact-sha256", artifact.hexdigest()]
    if args.work_dir is not None:
        command += ["--work-dir", str(args.work_dir.resolve())]
    subprocess.run(command, check=True, cwd=ROOT)
    report = json.loads(args.output.read_text())
    samples = report["rounds"]
    if len(samples) != args.rounds or not all(sample["verified"] and sample["size_bytes"] == args.size for sample in samples):
        raise SystemExit("Benchmark report did not verify every requested sample")
    for field in ["steady_mib_s", "total_mib_s"]:
        values = [sample[field] for sample in samples if sample[field] is not None]
        if values:
            print(f"{field}: median={statistics.median(values):.3f}, min={min(values):.3f}, max={max(values):.3f}")
    print("Evidence: virtual loopback, one JVM; not physical-client acceptance")


if __name__ == "__main__":
    main()
