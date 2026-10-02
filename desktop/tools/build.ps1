param([switch]$Run, [switch]$Package)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
function Invoke-JavaTool([string]$Tool, [string[]]$Arguments) {
    & (Join-Path $env:JAVA_HOME "bin/$Tool.exe") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
New-Item -ItemType Directory -Force build/lib, build/classes, build/tests | Out-Null
foreach ($line in Get-Content dependencies.txt) {
    $expected, $artifact = $line.Split(' ')
    $file = Join-Path 'build/lib' ($artifact.Split('/')[-1])
    if (!(Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/$artifact" -OutFile $file
        if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw "Dependency checksum mismatch: $artifact" }
    }
}
$sources = @((Get-ChildItem src/main/java -Recurse -Filter '*.java').FullName)
$sources += (Get-ChildItem ../app/src/main/java/dev/ghost/nearbyim/core -Filter '*.java').FullName
$sources += (Resolve-Path ../app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java).Path
# Java argument files must not start with a UTF-8 BOM (Windows PowerShell 5 adds one).
[IO.File]::WriteAllLines((Join-Path (Get-Location) 'build/sources.txt'), @($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), (New-Object Text.UTF8Encoding($false)))
Invoke-JavaTool javac @('--release', '17', '-encoding', 'UTF-8', '-cp', 'build/lib/*', '-d', 'build/classes', '@build/sources.txt')
Copy-Item src/main/resources/* build/classes -Recurse -Force
Copy-Item ../app/src/main/res/drawable-nodpi/ic_launcher_artwork.png build/classes/dev/ghost/wozai/app-icon.png -Force
Invoke-JavaTool jar @('--create', '--file', 'build/lib/wozai-desktop.jar', '--main-class', 'dev.ghost.wozai.Main', '-C', 'build/classes', '.')
$tests = (Get-ChildItem src/test/java -Recurse -Filter '*.java').FullName
Invoke-JavaTool javac (@('--release', '17', '-encoding', 'UTF-8', '-cp', 'build/classes;build/lib/*', '-d', 'build/tests') + $tests)
Invoke-JavaTool java @('-cp', 'build/classes;build/tests;build/lib/*', 'dev.ghost.wozai.DesktopTests')
if ($Package) {
    if (Test-Path build/package/WoZai) { Remove-Item build/package/WoZai -Recurse -Force }
    Invoke-JavaTool jpackage @('--type', 'app-image', '--name', 'WoZai', '--app-version', '0.2.0', '--vendor', 'GHOST-AKU', '--input', 'build/lib', '--main-jar', 'wozai-desktop.jar', '--main-class', 'dev.ghost.wozai.Main', '--dest', 'build/package', '--add-modules', 'java.base,java.desktop,java.logging,jdk.crypto.ec,jdk.accessibility,jdk.localedata', '--jlink-options', '--strip-debug --no-man-pages --no-header-files --include-locales=en,zh')
    Copy-Item ../THIRD_PARTY_NOTICES.md build/package/WoZai/
    Copy-Item ../docs/windows.md build/package/WoZai/README.md
    Copy-Item ../licenses build/package/WoZai/ -Recurse -Force
    Compress-Archive -Path build/package/WoZai -DestinationPath build/WoZai-0.2.0-windows-x64.zip -Force
}
if ($Run) { Invoke-JavaTool java @('-cp', 'build/lib/*', 'dev.ghost.wozai.Main') }
