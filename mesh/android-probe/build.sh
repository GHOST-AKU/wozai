#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
probe_sdk="${ANDROID_SDK_ROOT:-/workspace/toolchains/android-sdk}"
probe_java="${JAVA_HOME:-/workspace/toolchains/jdk-17.0.16+8}"
probe_tools="$probe_sdk/build-tools/35.0.0"
probe_out="${PROBE_BUILD_DIR:-$probe_dir/build}"
mkdir -p "$probe_out/classes"
"$probe_java/bin/javac" -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$probe_sdk/platforms/android-36/android.jar:$probe_tools/core-lambda-stubs.jar" \
    -d "$probe_out/classes" "$probe_dir/src/org/nearbymesh/probe/CapabilityProbe.java"
"$probe_java/bin/jar" cf "$probe_out/probe-classes.jar" -C "$probe_out/classes" .
JAVA_HOME="$probe_java" "$probe_tools/d8" --min-api 29 \
    --lib "$probe_sdk/platforms/android-36/android.jar" \
    --output "$probe_out" "$probe_out/probe-classes.jar"
if [[ "${1:-}" == "--unsigned-internal-apk" ]]; then
    "$probe_tools/aapt2" link -I "$probe_sdk/platforms/android-36/android.jar" \
        --manifest "$probe_dir/AndroidManifest.xml" -o "$probe_out/probe-resource.apk"
    python3 - "$probe_out" <<'PY'
from pathlib import Path
import sys, zipfile
out = Path(sys.argv[1])
with zipfile.ZipFile(out / 'probe-resource.apk') as source:
    with zipfile.ZipFile(out / 'probe-unsigned-internal.apk', 'w') as target:
        for item in source.infolist():
            target.writestr(item, source.read(item.filename))
        target.write(out / 'classes.dex', 'classes.dex')
PY
    printf '%s\n' "Internal unsigned package: $probe_out/probe-unsigned-internal.apk (not installable or an owner delivery)"
elif [[ $# -ne 0 ]]; then
    printf '%s\n' 'Usage: build.sh [--unsigned-internal-apk]' >&2
    exit 2
fi
printf '%s\n' "Compiled Java + DEX against Android API 36: $probe_out"
