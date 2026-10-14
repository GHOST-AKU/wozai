#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/wifi-direct-tests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/wifi-direct-tests \
    app/src/main/java/dev/ghost/nearbyim/core/StreamConnection.java \
    app/src/main/java/dev/ghost/nearbyim/transport/Peer.java \
    app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectPolicy.java \
    app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectSockets.java \
    tests/transport/WifiDirectPeerTests.java \
    tests/transport/WifiDirectPolicyTests.java \
    tests/transport/WifiDirectSocketTests.java
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectPeerTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectPolicyTests
java -cp build/wifi-direct-tests dev.ghost.nearbyim.transport.WifiDirectSocketTests
