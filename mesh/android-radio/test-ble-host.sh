#!/usr/bin/env bash
# Exercise actual BLE scheduling with narrow host doubles, never a radio/device claim.
set -euo pipefail
radio_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
radio_sdk="${ANDROID_SDK_ROOT:-/workspace/toolchains/android-sdk}"
radio_java="${JAVA_HOME:-/workspace/toolchains/jdk-17.0.16+8}"
radio_out="${RADIO_TEST_DIR:-$radio_dir/build/host-tests}"
mkdir -p "$radio_out"
radio_classes="$(mktemp -d "$radio_out/ble.XXXXXX")"
trap 'rm -rf -- "$radio_classes"' EXIT
mapfile -t radio_stubs < <(find "$radio_dir/tests/ble-host-stubs" -name '*.java' -type f | sort)
radio_sources=()
for radio_name in BleAdapter BleChunks CallbackBudget CoreBridge BytePipe RadioAdapter RadioConfig RadioEvents; do
    radio_sources+=("$radio_dir/src/org/nearbymesh/radio/$radio_name.java")
done
"$radio_java/bin/javac" --release 8 -cp "$radio_sdk/platforms/android-36/android.jar" -d "$radio_classes" \
    "${radio_stubs[@]}" "${radio_sources[@]}" "$radio_dir/tests/ble-host/BleAdapterHostTest.java"
"$radio_java/bin/java" -cp "$radio_classes:$radio_sdk/platforms/android-36/android.jar" org.nearbymesh.radio.BleAdapterHostTest
