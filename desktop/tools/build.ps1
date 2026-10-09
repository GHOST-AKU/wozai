param([switch]$Run, [switch]$Package)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& python ../tools/generate-i18n.py --check
if ($LASTEXITCODE -ne 0) { throw 'Translation catalogs or generated resources are invalid' }
& python ../tools/check-app-icons.py
if ($LASTEXITCODE -ne 0) { throw 'Formal app icon resources are invalid' }
$i18nConfig = Get-Content ../i18n/config.json -Raw -Encoding UTF8 | ConvertFrom-Json
$appVersion = $i18nConfig.appVersion
$runtimeLocales = ($i18nConfig.languages | ForEach-Object { $_.tag }) -join ','
function Invoke-JavaTool([string]$Tool, [string[]]$Arguments) {
    & (Join-Path $env:JAVA_HOME "bin/$Tool.exe") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
New-Item -ItemType Directory -Force build/lib, build/classes, build/tests | Out-Null
if(Test-Path build/lib/wozai-desktop.jar) { Remove-Item build/lib/wozai-desktop.jar -Force }
foreach ($line in Get-Content dependencies.txt) {
    $expected, $artifact = $line.Split(' ')
    $file = Join-Path 'build/lib' ($artifact.Split('/')[-1])
    if (!(Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/$artifact" -OutFile $file
        if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw "Dependency checksum mismatch: $artifact" }
    }
}
New-Item -ItemType Directory -Force build/fonts | Out-Null
foreach ($line in Get-Content font-dependencies.txt) {
    $expected, $url=$line.Split(' ')
    $file=Join-Path 'build/fonts' ($url.Split('/')[-1])
    if (!(Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        Invoke-WebRequest -Uri $url -OutFile $file
        if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw "Font checksum mismatch: $url" }
    }
}
& cmake -S native -B build/native -A x64
if ($LASTEXITCODE -ne 0) { throw 'Bluetooth CMake configuration failed' }
& cmake --build build/native --config Release
if ($LASTEXITCODE -ne 0) { throw 'Bluetooth native build failed' }
& ctest --test-dir build/native -C Release --output-on-failure
if ($LASTEXITCODE -ne 0) { throw 'Bluetooth native lifecycle tests failed' }
Copy-Item build/native/Release/wozai_bluetooth.dll build/lib/ -Force
$bluetoothLibrary = '-Dwozai.bluetooth.library=' + (Resolve-Path build/lib/wozai_bluetooth.dll).Path
$sources = @((Get-ChildItem src/main/java -Recurse -Filter '*.java').FullName)
$sources += (Get-ChildItem ../app/src/main/java/dev/ghost/nearbyim/core -Filter '*.java').FullName
$sources += (Get-ChildItem ../app/src/main/java/dev/ghost/nearbyim/noise -Recurse -Filter '*.java').FullName
$sources += (Get-ChildItem ../third_party/noise-java/src -Recurse -Filter '*.java').FullName
$sources += (Get-ChildItem ../app/src/main/java/dev/ghost/nearbyim/i18n -Filter '*.java' | Where-Object Name -ne 'I18nResources.java').FullName
$sources += (Resolve-Path ../app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java).Path
# Java argument files must not start with a UTF-8 BOM (Windows PowerShell 5 adds one).
[IO.File]::WriteAllLines((Join-Path (Get-Location) 'build/sources.txt'), @($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), (New-Object Text.UTF8Encoding($false)))
Invoke-JavaTool javac @('--release', '17', '-encoding', 'UTF-8', '-cp', 'build/lib/*', '-d', 'build/classes', '@build/sources.txt')
Copy-Item src/main/resources/* build/classes -Recurse -Force
New-Item -ItemType Directory -Force build/classes/dev/ghost/wozai/fonts | Out-Null
Copy-Item build/fonts/*.otf build/classes/dev/ghost/wozai/fonts/ -Force
if (Test-Path build/classes/dev/ghost/wozai/app-icon.png) { Remove-Item build/classes/dev/ghost/wozai/app-icon.png -Force }
if (Test-Path build/classes/dev/ghost/wozai/app-icons) { Remove-Item build/classes/dev/ghost/wozai/app-icons -Recurse -Force }
New-Item -ItemType Directory -Force build/classes/dev/ghost/wozai/app-icons | Out-Null
Copy-Item assets/icons/windows/nearbyim.ico build/classes/dev/ghost/wozai/app-icons/nearbyim.ico -Force
& python tools/write-build-metadata.py build/classes/dev/ghost/wozai/build-info.json
if ($LASTEXITCODE -ne 0) { throw 'Build metadata failed' }
Invoke-JavaTool jar @('--create', '--file', 'build/lib/nearbyim-desktop.jar', '--main-class', 'dev.ghost.wozai.Main', '-C', 'build/classes', '.')
$tests = (Get-ChildItem src/test/java -Recurse -Filter '*.java').FullName
Invoke-JavaTool javac (@('--release', '17', '-encoding', 'UTF-8', '-cp', 'build/classes;build/lib/*', '-d', 'build/tests') + $tests)
foreach ($test in @('DesktopTests', 'ReviewRegressionTests', 'DataLocationTests', 'NoiseIdentityTests', 'BluetoothTests', 'TransportTests', 'StringsTests', 'FontTests', 'MessagePaneTests', 'AttachmentStoreTests', 'AttachmentGuiTests', 'AppIconTests')) {
    Invoke-JavaTool java @($bluetoothLibrary, '-cp', 'build/classes;build/tests;build/lib/*', "dev.ghost.wozai.$test")
}
if ($Package) {
    # Share constant strings; keep modules compressible by the outer ZIP.
    if (Test-Path build/package/NearbyIM) { Remove-Item build/package/NearbyIM -Recurse -Force }
    Invoke-JavaTool jpackage @('--type', 'app-image', '--name', 'NearbyIM', '--app-version', $appVersion, '--vendor', 'GHOST-AKU', '--input', 'build/lib', '--main-jar', 'nearbyim-desktop.jar', '--main-class', 'dev.ghost.wozai.Main', '--dest', 'build/package', '--icon', 'assets/icons/windows/nearbyim.ico', '--java-options', '-Dwozai.installDir=$APPDIR/..', '--add-modules', 'java.base,java.desktop,java.logging,jdk.crypto.ec,jdk.accessibility,jdk.localedata', '--jlink-options', "--strip-debug --no-man-pages --no-header-files --compress=1 --include-locales=$runtimeLocales")
    Copy-Item ../THIRD_PARTY_NOTICES.md build/package/NearbyIM/
    Copy-Item ../docs/windows.md build/package/NearbyIM/README.md
    Copy-Item ../licenses build/package/NearbyIM/ -Recurse -Force
    Copy-Item ../docs/licenses/material-icons-LICENSE.txt build/package/NearbyIM/licenses/ -Force
    Compress-Archive -Path build/package/NearbyIM -DestinationPath "build/NearbyIM-$appVersion-windows-x64.zip" -Force
}
if ($Run) { Invoke-JavaTool java @($bluetoothLibrary, '-cp', 'build/lib/*', 'dev.ghost.wozai.Main') }
