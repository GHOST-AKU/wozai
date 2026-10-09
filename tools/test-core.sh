#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/core-tests
find third_party/noise-java/src app/src/main/java/dev/ghost/nearbyim/noise -name '*.java' > build/core-tests/crypto-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests @build/core-tests/crypto-sources.txt app/src/main/java/dev/ghost/nearbyim/core/*.java app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java tests/*.java
java -cp build/core-tests dev.ghost.nearbyim.core.CoreTests "$@"
java -cp build/core-tests dev.ghost.nearbyim.core.AuthenticationTests
java -cp build/core-tests dev.ghost.nearbyim.core.AttachmentTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLargeFileTest

java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ImageSafetyTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBenchmarkTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentSourceTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2ControlTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2Tests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentResumeTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLifecycleReviewTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ProtocolV4Tests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FairRecordWriterTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SecureSessionTests
