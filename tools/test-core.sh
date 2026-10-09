#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/core-tests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests app/src/main/java/dev/ghost/nearbyim/core/*.java app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java tests/*.java
java -cp build/core-tests dev.ghost.nearbyim.core.CoreTests "$@"
java -cp build/core-tests dev.ghost.nearbyim.core.AuthenticationTests
java -cp build/core-tests dev.ghost.nearbyim.core.AttachmentTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLargeFileTest

java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ImageSafetyTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBenchmarkTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentSourceTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2ControlTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2Tests
