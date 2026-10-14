$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    New-Item -ItemType Directory -Force -Path "build/core-tests" | Out-Null
    $sourceFiles = @((Get-ChildItem "app/src/main/java/dev/ghost/nearbyim/core/*.java").FullName) + @((Get-ChildItem "tests/*.java").FullName)
    $sourceFiles += (Resolve-Path app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java).Path
    $sourceFiles += (Resolve-Path app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java).Path
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
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.TransferPathTests
    if($LASTEXITCODE -ne 0) { throw 'Transfer path tests failed' }
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
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentQueueSessionTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.PhotoExportStoreTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentCheckpointLatencyTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentFeedbackTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentResumeTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentLifecycleReviewTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.ProtocolV4Tests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.FairRecordWriterTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SecureSessionTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.SafetyCodeTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests app/src/main/java/dev/ghost/nearbyim/QrCameraFocus.java app/src/main/java/dev/ghost/nearbyim/QrFocusPoint.java tests/camera/QrCameraFocusTests.java tests/camera/QrFocusPointTests.java
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.QrCameraFocusTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.QrFocusPointTests
    exit $LASTEXITCODE
} finally { Pop-Location }
