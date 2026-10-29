$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force build/lib, build/fonts, build/classes, build/tests | Out-Null
foreach ($line in Get-Content dependencies.txt) {
    $expected, $artifact=$line.Split(' ')
    $target=Join-Path build/lib ($artifact.Split('/')[-1])
    if (!(Test-Path $target) -or (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        Invoke-WebRequest "https://repo.maven.apache.org/maven2/$artifact" -OutFile $target
        if ((Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Dependency checksum mismatch' }
    }
}
foreach ($line in Get-Content font-dependencies.txt) {
    $expected, $url=$line.Split(' ')
    $target=Join-Path build/fonts ($url.Split('/')[-1])
    if (!(Test-Path $target) -or (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        Invoke-WebRequest $url -OutFile $target
        if ((Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Font checksum mismatch' }
    }
}
$sources=@((Get-ChildItem src/main/java -Recurse -Filter '*.java').FullName)
foreach ($folder in @('core','noise','verification','i18n')) {
    $sources+=(Get-ChildItem "../app/src/main/java/dev/ghost/nearbyim/$folder" -Recurse -Filter '*.java' | Where-Object Name -ne 'I18nResources.java').FullName
}
$sources+=(Get-ChildItem ../third_party/noise-java/src -Recurse -Filter '*.java').FullName
$sources+=(Resolve-Path ../app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java).Path
[IO.File]::WriteAllLines((Join-Path (Get-Location) 'build/receipt-sources.txt'), @($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), (New-Object Text.UTF8Encoding($false)))
& "$env:JAVA_HOME/bin/javac.exe" --release 17 -encoding UTF-8 -cp 'build/lib/*' -d build/classes '@build/receipt-sources.txt'
if ($LASTEXITCODE -ne 0) { throw 'Client compilation failed' }
Copy-Item src/main/resources/* build/classes -Recurse -Force
New-Item -ItemType Directory -Force build/classes/dev/ghost/wozai/fonts, build/classes/dev/ghost/wozai/app-icons | Out-Null
Copy-Item build/fonts/*.otf build/classes/dev/ghost/wozai/fonts -Force
Copy-Item assets/icons/windows/nearbyim.ico build/classes/dev/ghost/wozai/app-icons/nearbyim.ico -Force
$tests=@('DesktopTests','ReadReceiptStoreTests','TestNoise','MessagePaneTests','ReceiptGuiTests','ReadReceiptGuiTests') | ForEach-Object { "src/test/java/dev/ghost/wozai/$_.java" }
& "$env:JAVA_HOME/bin/javac.exe" --release 17 -encoding UTF-8 -cp 'build/classes;build/lib/*' -d build/tests @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
foreach ($test in @('DesktopTests','ReadReceiptStoreTests','MessagePaneTests','ReceiptGuiTests','ReadReceiptGuiTests')) {
    & "$env:JAVA_HOME/bin/java.exe" -cp 'build/classes;build/tests;build/lib/*' "dev.ghost.wozai.$test" build/receipt-windows
    if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
