#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/core-tests
find third_party/noise-java/src app/src/main/java/dev/ghost/nearbyim/noise -name '*.java' > build/core-tests/crypto-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests @build/core-tests/crypto-sources.txt app/src/main/java/dev/ghost/nearbyim/core/*.java app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java app/src/main/java/dev/ghost/nearbyim/GalleryExportNames.java tests/*.java
java -cp build/core-tests dev.ghost.nearbyim.core.CoreTests "$@"
java -cp build/core-tests dev.ghost.nearbyim.core.AuthenticationTests
java -cp build/core-tests dev.ghost.nearbyim.core.AttachmentTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLargeFileTest

java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ImageSafetyTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AvatarTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AvatarSyncTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentExportStoreTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentDirectoryStoreTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.TransferPathTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AdaptiveLayoutTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBenchmarkTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentSourceTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2ControlTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2Tests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentTerminalTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBatchTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBatchReviewTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRetryTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteQueueTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentQueueSessionTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.PhotoExportStoreTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.GalleryExportNameTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentCheckpointLatencyTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentFeedbackTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteWindowTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentResumeTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLifecycleReviewTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ProtocolV4Tests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkProtocolTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkCapabilityTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkProbeTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SessionCloseRaceTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkWriterOrderingTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.LateFileLanCallbackTests
java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.ExistingHotspotQualityTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileDataPathsTests
java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileHandoverTests
java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.FileQualityRequestRetryTests
java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.PausedQueueDirectGroupTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileRouteQualityPolicyTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FairRecordWriterTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileRoutesTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.DirectGroupControlTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.DirectGroupRoutingTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ExistingWifiReuseTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileRouteDiagnosticsTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileRouteDiagnosticsTests --slow-direct
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteDisconnectTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteRecoveryTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileRecoveryLeaseTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SecureSessionTests
java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SafetyCodeTests
sh tools/test-qr-camera-focus.sh
sh tools/test-wifi-direct.sh
