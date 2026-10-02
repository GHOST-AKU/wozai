$ErrorActionPreference = 'Stop'
$package = (Resolve-Path desktop/build/package/WoZai).Path
$dataPath = Join-Path $env:RUNNER_TEMP ('wozai-package-' + [Guid]::NewGuid().ToString('N'))
$previousOptions = $env:JAVA_TOOL_OPTIONS
$process = $null
$windowProcess = $null
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WoZaiWindow {
    [DllImport("user32.dll", CharSet=CharSet.Unicode)]
    public static extern int GetClassName(IntPtr window, StringBuilder name, int maximum);
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr window, out Rect rectangle);
}
'@
Add-Type -AssemblyName System.Drawing
try {
    if (!(Test-Path (Join-Path $package 'runtime/bin/jabswitch.exe'))) { throw 'Packaged Java Access Bridge tool is missing' }
    if (!(Test-Path (Join-Path $package 'runtime/legal/java.base/LICENSE'))) { throw 'Packaged runtime license is missing' }
    # Appended options apply only to this generated test profile and are restored below.
    $env:JAVA_TOOL_OPTIONS = "$previousOptions -Dwozai.dataDir=$dataPath -Duser.language=zh -Duser.country=CN"
    $process = Start-Process (Join-Path $package 'WoZai.exe') -WorkingDirectory $package -PassThru
    $windowProcess = $process
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 200
        $process.Refresh()
        if ($process.HasExited) { throw 'Packaged launcher exited before displaying its window' }
        # JDK 17 jpackage restarts itself in a child after preparing its DLL PATH.
        # The parent has a message-only window; the child owns the actual Swing UI.
        if ($windowProcess.Id -eq $process.Id -and $process.MainWindowHandle -eq 0) {
            $child = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id) AND Name='WoZai.exe'" | Select-Object -First 1
            if ($child) { $windowProcess = Get-Process -Id $child.ProcessId }
        }
        $windowProcess.Refresh()
    } while ($windowProcess.MainWindowHandle -eq 0 -and [DateTime]::UtcNow -lt $deadline)
    $class = New-Object Text.StringBuilder 256
    [WoZaiWindow]::GetClassName($windowProcess.MainWindowHandle, $class, $class.Capacity) | Out-Null
    if ($class.ToString() -ne 'SunAwtFrame' -or $windowProcess.MainWindowTitle -ne '我在') {
        Write-Output "Launcher observation: PID=$($process.Id), windowPID=$($windowProcess.Id), handle=$($windowProcess.MainWindowHandle), class=$($class.ToString()), title=$($windowProcess.MainWindowTitle)"
        Get-Process -Name WoZai, java, javaw -ErrorAction SilentlyContinue | Select-Object Id, ProcessName, MainWindowHandle, MainWindowTitle | Format-Table | Out-String | Write-Output
        Get-CimInstance Win32_Process -Filter "Name='WoZai.exe'" | Select-Object ProcessId, ParentProcessId | Format-Table | Out-String | Write-Output
        Get-Content (Join-Path $package 'app/WoZai.cfg') | Write-Output
        throw 'Packaged launcher did not display the chat window'
    }
    if (!(Test-Path (Join-Path $dataPath 'identity.properties'))) { throw 'Packaged runtime did not create its DPAPI identity' }
    $rectangle = New-Object WoZaiWindow+Rect
    if (![WoZaiWindow]::GetWindowRect($windowProcess.MainWindowHandle, [ref]$rectangle)) { throw 'Could not inspect packaged window' }
    $bitmap = New-Object Drawing.Bitmap ($rectangle.Right - $rectangle.Left), ($rectangle.Bottom - $rectangle.Top)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($rectangle.Left, $rectangle.Top, 0, 0, $bitmap.Size)
        $bitmap.Save((Join-Path (Get-Location) 'desktop/build/package-window.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
    $windowProcess.CloseMainWindow() | Out-Null
    if (!$process.WaitForExit(15000) -or $process.ExitCode -ne 0) { throw 'Packaged launcher did not exit cleanly' }
    Write-Output 'Package smoke: WoZai.exe, bundled runtime, DPAPI identity, Chinese UI, accessibility tool, runtime license and clean exit passed'
} finally {
    if ($windowProcess -and !$windowProcess.HasExited) { Stop-Process -Id $windowProcess.Id -Force }
    if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id -Force }
    $env:JAVA_TOOL_OPTIONS = $previousOptions
    if (Test-Path $dataPath) { Remove-Item $dataPath -Recurse -Force }
}
