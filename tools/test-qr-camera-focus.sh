#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/qr-camera-focus-tests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/qr-camera-focus-tests \
    app/src/main/java/dev/ghost/nearbyim/QrCameraFocus.java app/src/main/java/dev/ghost/nearbyim/QrFocusPoint.java app/src/main/java/dev/ghost/nearbyim/QrPreviewGeometry.java tests/camera/QrCameraFocusTests.java tests/camera/QrFocusPointTests.java tests/camera/QrPreviewGeometryTests.java
java -cp build/qr-camera-focus-tests dev.ghost.nearbyim.QrCameraFocusTests
java -cp build/qr-camera-focus-tests dev.ghost.nearbyim.QrFocusPointTests

java -cp build/qr-camera-focus-tests dev.ghost.nearbyim.QrPreviewGeometryTests
