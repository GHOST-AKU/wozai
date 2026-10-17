#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/wifi-direct-tests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/wifi-direct-tests \
    app/src/main/java/dev/ghost/nearbyim/core/StreamConnection.java \
    app/src/main/java/dev/ghost/nearbyim/core/SocketPath.java \
    app/src/main/java/dev/ghost/nearbyim/core/LocalEndpoint.java \
    app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java \
    app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java \
    app/src/main/java/dev/ghost/nearbyim/transport/Peer.java \
    app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectPolicy.java \
    app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectDiagnostics.java \
    app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectSockets.java \
    tests/transport/WifiDirectPeerTests.java \
    tests/transport/WifiDirectPolicyTests.java \
    tests/transport/WifiDirectSocketTests.java \
    tests/transport/AutomaticDirectTargetTests.java \
    tests/transport/WifiDirectDiscoveryTests.java \
    tests/transport/WifiDirectKnownGroupTests.java
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectPeerTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectPolicyTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectSocketTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.AutomaticDirectTargetTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectDiscoveryTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectKnownGroupTests
