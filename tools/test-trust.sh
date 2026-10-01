#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/trust-tests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/trust-tests app/src/main/java/dev/ghost/nearbyim/storage/*.java tests/trust/*.java
java -cp build/trust-tests dev.ghost.nearbyim.storage.TrustPolicyTests
python3 tests/trust/store_tests.py build/trust-tests
