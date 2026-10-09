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
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentBenchmarkTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentSourceTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2ControlTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -Xmx32m -cp build/core-tests dev.ghost.nearbyim.core.AttachmentV2Tests
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
    exit $LASTEXITCODE
} finally { Pop-Location }
