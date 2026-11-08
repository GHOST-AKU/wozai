param([switch]$Test)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $root
function Check([string]$message) { if ($LASTEXITCODE -ne 0) { throw $message } }
$config = Get-Content i18n/config.json -Raw -Encoding UTF8 | ConvertFrom-Json
cargo build --manifest-path mesh/Cargo.toml --locked --release
Check 'Native Mesh core build failed'
$env:WOZAI_MESH_CORE = Join-Path $root 'mesh/target/release/mesh-lab.exe'
if ($Test) {
    python windows-next/tools/build-bridge.py --test
    Check 'Real-kernel bridge tests failed'
    dotnet test windows-next/Tests/WindowsNext.Transport.Tests.csproj --configuration Release
    Check 'Transport tests failed'
} else {
    python windows-next/tools/build-bridge.py
    Check 'Java bridge compilation failed'
}
cmake -S desktop/native -B windows-next/build/native -A x64
Check 'Bluetooth CMake configuration failed'
cmake --build windows-next/build/native --config Release
Check 'Bluetooth native build failed'
ctest --test-dir windows-next/build/native -C Release --output-on-failure
Check 'Bluetooth native checks failed'
$package = Join-Path $root 'windows-next/build/package/WoZaiNext'
dotnet publish windows-next/WindowsNext/WindowsNext.csproj -c Release -r win-x64 --self-contained true -p:Platform=x64 -p:Version=$($config.appVersion.Split('-')[0]) -o $package
Check 'WinUI publish failed'
if ((Test-Path "$package/onnxruntime.dll") -or (Test-Path "$package/DirectML.dll")) { throw 'Unused AI/ML runtime was bundled' }
$pri = Join-Path $package 'resources.pri'
Get-ChildItem windows-next/WindowsNext,$package -Recurse -Filter '*.pri' | Select-Object FullName,Length | Format-Table -AutoSize
if (-not (Test-Path $pri)) { throw 'WinUI resource index was not published; default control styles cannot load' }
$makePri = Get-ChildItem "${env:ProgramFiles(x86)}/Windows Kits/10/bin/*/x64/makepri.exe" | Sort-Object FullName -Descending | Select-Object -First 1
if ($null -eq $makePri) { throw 'Windows SDK makepri.exe is required to validate native resources' }
$priDump = Join-Path $root 'windows-next/build/native-resources.xml'
& $makePri.FullName dump /if $pri /of $priDump /dt detailed /o
Check 'WinUI resource index inspection failed'
if (-not (Select-String -Path $priDump -SimpleMatch 'Microsoft.UI.Xaml/Themes/generic' -Quiet)) { throw 'Published PRI lacks WinUI default styles' }
Write-Output 'Published PRI contains WinUI default control styles.'
$kernel = Join-Path $package 'kernel'
New-Item -ItemType Directory -Force "$kernel/lib" | Out-Null
Copy-Item mesh/target/release/mesh-lab.exe "$kernel/mesh-lab.exe"
Copy-Item mesh/THIRD_PARTY.md "$kernel/MESH-THIRD-PARTY.md"
Copy-Item mesh/vendor-licenses "$kernel/mesh-licenses" -Recurse -Force
Copy-Item windows-next/build/bridge/lib/*.jar "$kernel/lib/"
Copy-Item windows-next/build/native/Release/wozai_bluetooth.dll "$kernel/lib/"
& "$env:JAVA_HOME/bin/jar.exe" --create --file "$kernel/lib/windows-next-bridge.jar" -C windows-next/build/bridge/classes .
Check 'Bridge JAR packaging failed'
if (Test-Path "$kernel/runtime") { Remove-Item "$kernel/runtime" -Recurse -Force }
& "$env:JAVA_HOME/bin/jlink.exe" --add-modules java.base,java.desktop,java.logging,java.naming,java.management,java.sql,jdk.crypto.ec,jdk.localedata,jdk.unsupported --strip-debug --no-man-pages --no-header-files --compress=2 --output "$kernel/runtime"
Check 'Bundled Java runtime failed'
# The Rust MSVC child imports VCRUNTIME140. App-local placement also works on a
# clean Windows installation without a separately installed VC redistributable.
$crt = Join-Path $kernel 'runtime/bin/vcruntime140.dll'
if (-not (Test-Path $crt)) { throw 'Bundled JDK lacks required app-local CRT' }
Copy-Item $crt "$kernel/vcruntime140.dll"
if ((Get-FileHash $crt).Hash -ne (Get-FileHash "$kernel/vcruntime140.dll").Hash) { throw 'CRT copy mismatch' }
Copy-Item THIRD_PARTY_NOTICES.md, LICENSE $package
Copy-Item licenses "$package/licenses" -Recurse -Force
Copy-Item docs/windows-next.md "$package/README.md"
@{version=$config.appVersion;buildNumber=$config.buildNumber;source=(git rev-parse HEAD);kernel='DesktopClient + independent Mesh Core';platform='win-x64';ui='WinUI 3';meshCoreSha256=(Get-FileHash "$kernel/mesh-lab.exe" -Algorithm SHA256).Hash.ToLowerInvariant()} | ConvertTo-Json | Set-Content "$package/build-info.json" -Encoding UTF8
$zip = Join-Path $root "windows-next/build/WoZai-next-$($config.appVersion)-windows-x64.zip"
Compress-Archive -Path $package -DestinationPath $zip -Force
Get-FileHash $zip -Algorithm SHA256 | Format-List
Write-Output ("Product ZIP bytes: " + (Get-Item $zip).Length)
Write-Output ("Mesh Core bytes: " + (Get-Item "$kernel/mesh-lab.exe").Length)
