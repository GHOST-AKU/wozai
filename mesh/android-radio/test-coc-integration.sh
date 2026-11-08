#!/usr/bin/env bash
set -euo pipefail
radio_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
radio_java="${JAVA_HOME:-/workspace/toolchains/jdk-17.0.16+8}"
radio_core="${MESH_LAB_BINARY:-$radio_dir/../target/release/mesh-lab}"
test -x "$radio_core"
radio_classes="$(mktemp -d)"
trap 'rm -rf -- "$radio_classes"' EXIT
sources=()
for name in RadioConfig CoreBridge BytePipe CocProtocol CocRuntime CocStreams; do sources+=("$radio_dir/src/org/nearbymesh/radio/$name.java"); done
"$radio_java/bin/javac" --release 8 -d "$radio_classes" "${sources[@]}" "$radio_dir/tests/CocStreamsTest.java" "$radio_dir/tests/CocCoreIntegrationTest.java"
"$radio_java/bin/java" -cp "$radio_classes" org.nearbymesh.radio.CocCoreIntegrationTest "$radio_core"
