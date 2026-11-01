#!/usr/bin/env bash
set -euo pipefail
radio_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
radio_sdk="${ANDROID_SDK_ROOT:-/workspace/toolchains/android-sdk}"
radio_java="${JAVA_HOME:-/workspace/toolchains/jdk-17.0.16+8}"
radio_tools="$radio_sdk/build-tools/35.0.0"
radio_out="${RADIO_BUILD_DIR:-$radio_dir/build}"
mkdir -p "$radio_out"
radio_classes="$(mktemp -d "$radio_out/classes.XXXXXX")"
trap 'rm -rf -- "$radio_classes"' EXIT
mapfile -t radio_sources < <(find "$radio_dir/src" -name '*.java' -type f | sort)
"$radio_java/bin/javac" -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$radio_sdk/platforms/android-36/android.jar:$radio_tools/core-lambda-stubs.jar" \
    -d "$radio_classes" "${radio_sources[@]}"
"$radio_java/bin/jar" cf "$radio_out/radio-classes.jar" -C "$radio_classes" .
JAVA_HOME="$radio_java" "$radio_tools/d8" --min-api 29 --lib "$radio_sdk/platforms/android-36/android.jar" --output "$radio_out" "$radio_out/radio-classes.jar"
if [[ "${1:-}" == "--unsigned-internal-apk" ]]; then
    "$radio_tools/aapt2" link -I "$radio_sdk/platforms/android-36/android.jar" --manifest "$radio_dir/AndroidManifest.xml" -o "$radio_out/radio-resource.apk"
    python3 - "$radio_out" <<'PY'
from pathlib import Path
import sys,zipfile
out=Path(sys.argv[1])
with zipfile.ZipFile(out/'radio-resource.apk') as source,zipfile.ZipFile(out/'radio-unsigned-internal.apk','w') as target:
    for item in source.infolist():target.writestr(item,source.read(item.filename))
    target.write(out/'classes.dex','classes.dex')
PY
    printf '%s\n' 'Unsigned internal package only; not an owner-installable delivery.'
elif [[ $# -ne 0 ]]; then
    printf '%s\n' 'Usage: build.sh [--unsigned-internal-apk]' >&2
    exit 2
fi
printf '%s\n' "Android API36 Java + DEX compiled: $radio_out"
