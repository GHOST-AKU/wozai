$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
New-Item -ItemType Directory -Force build/trust-tests | Out-Null
$sources = @(Get-ChildItem app/src/main/java/dev/ghost/nearbyim/storage/*.java, tests/trust/*.java | ForEach-Object FullName)
& java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/trust-tests $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp build/trust-tests dev.ghost.nearbyim.storage.TrustPolicyTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& python tests/trust/store_tests.py build/trust-tests
exit $LASTEXITCODE
