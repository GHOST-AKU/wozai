$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    New-Item -ItemType Directory -Force build/wifi-direct-tests | Out-Null
    $sources = @(
        "app/src/main/java/dev/ghost/nearbyim/core/StreamConnection.java",
        "app/src/main/java/dev/ghost/nearbyim/core/SocketPath.java",
        "app/src/main/java/dev/ghost/nearbyim/core/LocalEndpoint.java",
        "app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java",
        "app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java",
        "app/src/main/java/dev/ghost/nearbyim/transport/Peer.java",
        "app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectPolicy.java",
        "app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectDiagnostics.java",
        "app/src/main/java/dev/ghost/nearbyim/transport/WifiDirectSockets.java"
    ) + @((Get-ChildItem tests/transport/*.java).FullName)
    & java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/wifi-direct-tests @sources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    foreach ($fixture in @("WifiDirectPeerTests", "WifiDirectPolicyTests", "WifiDirectSocketTests", "AutomaticDirectTargetTests", "WifiDirectDiscoveryTests", "WifiDirectKnownGroupTests", "WifiDirectGroupReadinessTests")) {
        & java -cp build/wifi-direct-tests "dev.ghost.nearbyim.transport.$fixture"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} finally { Pop-Location }
