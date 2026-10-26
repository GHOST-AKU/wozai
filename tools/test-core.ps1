$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    New-Item -ItemType Directory -Force -Path "build/core-tests" | Out-Null
    $sourceFiles = @((Get-ChildItem "app/src/main/java/dev/ghost/nearbyim/core/*.java").FullName) + @((Get-ChildItem "tests/*.java").FullName)
    $sourceFiles += (Resolve-Path app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java).Path
    $sourceFiles += (Resolve-Path app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java).Path
    $sourceFiles += (Resolve-Path app/src/main/java/dev/ghost/nearbyim/GalleryExportNames.java).Path
    $sourceFiles += (Get-ChildItem app/src/main/java/dev/ghost/nearbyim/noise -Recurse -Filter '*.java').FullName
    $sourceFiles += (Get-ChildItem third_party/noise-java/src -Recurse -Filter '*.java').FullName
    & java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests @sourceFiles
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.core.CoreTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.core.AuthenticationTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.core.AttachmentTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLargeFileTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ImageSafetyTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AvatarTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AvatarSyncTests
    if($LASTEXITCODE -ne 0) { throw 'Avatar sync tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentExportStoreTests
    if($LASTEXITCODE -ne 0) { throw 'Attachment export location tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentDirectoryStoreTests
    if($LASTEXITCODE -ne 0) { throw 'Receive-directory journal tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.TransferPathTests
    if($LASTEXITCODE -ne 0) { throw 'Transfer path tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AdaptiveLayoutTests
    if($LASTEXITCODE -ne 0) { throw 'Adaptive layout tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileRoutesTests
    if($LASTEXITCODE -ne 0) { throw 'Automatic file route tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ExistingWifiReuseTests
    if($LASTEXITCODE -ne 0) { throw 'Existing Wi-Fi reuse tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.DirectGroupControlTests
    if($LASTEXITCODE -ne 0) { throw 'Direct group control tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.DirectGroupRoutingTests
    if($LASTEXITCODE -ne 0) { throw 'Direct group routing tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileRouteDiagnosticsTests
    if($LASTEXITCODE -ne 0) { throw 'File route diagnostics tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileRouteDiagnosticsTests --slow-direct
    if($LASTEXITCODE -ne 0) { throw 'Slow direct Wi-Fi diagnostics tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteDisconnectTests
    if($LASTEXITCODE -ne 0) { throw 'File route disconnect checkpoint tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteRecoveryTests
    if($LASTEXITCODE -ne 0) { throw 'Automatic file route recovery tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileRecoveryLeaseTests
    if($LASTEXITCODE -ne 0) { throw 'File recovery lease lifecycle tests failed' }
    & ./tools/test-wifi-direct.ps1
    if($LASTEXITCODE -ne 0) { throw 'Direct Wi-Fi policy/socket tests failed' }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBenchmarkTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentSourceTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2ControlTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2Tests
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentTerminalTests
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBatchTests
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBatchReviewTests
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRetryTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteQueueTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentQueueSessionTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.PhotoExportStoreTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.GalleryExportNameTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentCheckpointLatencyTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentFeedbackTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentRouteWindowTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentResumeTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLifecycleReviewTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ProtocolV4Tests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkProtocolTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkCapabilityTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkProbeTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SessionCloseRaceTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileLinkWriterOrderingTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.LateFileLanCallbackTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.ExistingHotspotQualityTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileDataPathsTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.AutomaticFileHandoverTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.FileQualityRequestRetryTests
    if($LASTEXITCODE -ne 0) { throw 'Quality request retry tests failed' }
    & java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.PausedQueueDirectGroupTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FileRouteQualityPolicyTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FairRecordWriterTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SecureSessionTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SafetyCodeTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests app/src/main/java/dev/ghost/nearbyim/QrCameraFocus.java app/src/main/java/dev/ghost/nearbyim/QrFocusPoint.java app/src/main/java/dev/ghost/nearbyim/QrPreviewGeometry.java tests/camera/QrCameraFocusTests.java tests/camera/QrFocusPointTests.java tests/camera/QrPreviewGeometryTests.java
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.QrCameraFocusTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.QrFocusPointTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.QrPreviewGeometryTests
    exit $LASTEXITCODE
} finally { Pop-Location }
