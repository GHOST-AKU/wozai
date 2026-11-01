#!/usr/bin/env bash
set -euo pipefail
radio_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
radio_java="${JAVA_HOME:-/workspace/toolchains/jdk-17.0.16+8}"
radio_out="${RADIO_TEST_DIR:-$radio_dir/build/host-tests}"
mkdir -p "$radio_out"
radio_classes="$(mktemp -d "$radio_out/classes.XXXXXX")"
trap 'rm -rf -- "$radio_classes"' EXIT
mapfile -t radio_tests < <(find "$radio_dir/tests" -name '*Test.java' -type f | sort)
"$radio_java/bin/javac" -source 8 -target 8 -Xlint:-options -d "$radio_classes" \
    "$radio_dir/src/org/nearbymesh/radio/RadioConfig.java" \
    "$radio_dir/src/org/nearbymesh/radio/CoreBridge.java" \
    "$radio_dir/src/org/nearbymesh/radio/BleChunks.java" \
    "$radio_dir/src/org/nearbymesh/radio/CallbackBudget.java" \
    "$radio_dir/src/org/nearbymesh/radio/AwareOffer.java" \
    "${radio_tests[@]}"
for radio_test in RadioConfigTest CoreBridgeTest BleChunksTest CallbackBudgetTest AwareOfferTest; do
    "$radio_java/bin/java" -cp "$radio_classes" "org.nearbymesh.radio.${radio_test}"
done
