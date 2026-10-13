#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/qr-camera-focus-tests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/qr-camera-focus-tests \
    app/src/main/java/dev/ghost/nearbyim/QrCameraFocus.java tests/camera/QrCameraFocusTests.java
java -cp build/qr-camera-focus-tests dev.ghost.nearbyim.QrCameraFocusTests
