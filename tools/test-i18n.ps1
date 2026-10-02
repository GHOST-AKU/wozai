$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& python tools/generate-i18n.py --check
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& python tools/check-i18n.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& python tests/i18n/generator_tests.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force build/i18n-tests | Out-Null
$sources = (Get-ChildItem app/src/main/java/dev/ghost/nearbyim/i18n -Filter '*.java' | Where-Object Name -ne 'I18nResources.java').FullName
$sources += (Resolve-Path tests/i18n/LanguageRegistryTests.java).Path
& javac -encoding UTF-8 -d build/i18n-tests $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp build/i18n-tests dev.ghost.nearbyim.i18n.LanguageRegistryTests
exit $LASTEXITCODE
