$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    New-Item -ItemType Directory -Force -Path "build/core-tests" | Out-Null
    $sourceFiles = @((Get-ChildItem "app/src/main/java/dev/ghost/nearbyim/core/*.java").FullName) + @((Get-ChildItem "tests/*.java").FullName)
    & java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/core-tests @sourceFiles
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & java -cp build/core-tests dev.ghost.nearbyim.core.CoreTests
    exit $LASTEXITCODE
} finally { Pop-Location }
