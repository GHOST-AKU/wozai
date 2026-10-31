param([string]$Package = 'windows-next/build/package/WoZaiNext', [string]$Output = 'windows-next/build/screenshots')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $root
$Package = (Resolve-Path $Package).Path
$Output = [IO.Path]::GetFullPath((Join-Path $root $Output))
New-Item -ItemType Directory -Force $Output | Out-Null
$temporaryRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$captureProfile = Join-Path $temporaryRoot ('wozai-next-capture-' + [Guid]::NewGuid().ToString('N'))
$classpath = "$root/windows-next/build/bridge/classes;$root/windows-next/build/bridge/tests;$root/windows-next/build/bridge/lib/*"
& "$env:JAVA_HOME/bin/java.exe" "-Djava.awt.headless=true" -cp $classpath dev.ghost.wozai.next.BridgeFixtures seed $captureProfile
if ($LASTEXITCODE -ne 0) { throw 'Screenshot fixture creation failed' }
Add-Type -AssemblyName System.Drawing, System.Windows.Forms, UIAutomationClient, UIAutomationTypes
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class NextCaptureNative {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h,int n);
 [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h,int x,int y,int w,int z,bool repaint);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h,int attribute,out RECT rect,int size);
}
'@
[NextCaptureNative]::SetProcessDPIAware() | Out-Null
if (Get-Command Set-DisplayResolution -ErrorAction SilentlyContinue) {
    try { Set-DisplayResolution -Width 1600 -Height 1000 -Force | Out-Null } catch { }
}
$env:WOZAI_NEXT_DATA_DIR = $captureProfile
$env:WOZAI_NEXT_STARTUP_LOG = "$Output/startup.txt"
$exe = Join-Path $Package 'WindowsNext.exe'
$app = Start-Process $exe -PassThru -WorkingDirectory $Package -RedirectStandardError "$Output/stderr.txt" -RedirectStandardOutput "$Output/stdout.txt"
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 400
        $app.Refresh()
        if ($app.HasExited) { throw "WinUI exited before showing a window: $($app.ExitCode)" }
    } while ($app.MainWindowHandle -eq 0 -and [DateTime]::UtcNow -lt $deadline)
    $hwnd = $app.MainWindowHandle
    if ($hwnd -eq 0) { throw 'No real WinUI window was created' }
    [NextCaptureNative]::ShowWindow($hwnd, 9) | Out-Null
    $screen = [Windows.Forms.Screen]::PrimaryScreen.Bounds
    [NextCaptureNative]::MoveWindow($hwnd, 20, 20, [Math]::Min(1200,$screen.Width-40), [Math]::Min(800,$screen.Height-40), $true) | Out-Null
    [NextCaptureNative]::SetForegroundWindow($hwnd) | Out-Null
    $window = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
    function Find-Control([string]$id) {
        $condition = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::AutomationIdProperty, $id)
        $window = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
        $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
    }
    function Click-Control([string]$id) {
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $control = Find-Control $id
            if ($null -eq $control) { Start-Sleep -Milliseconds 200 }
        } while ($null -eq $control -and [DateTime]::UtcNow -lt $deadline)
        if ($null -eq $control) { throw "Missing native UI control: $id" }
        $pattern = $null
        if ($control.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)) { $pattern.Invoke(); return }
        if ($control.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)) { $pattern.Select(); return }
        if ($control.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern,[ref]$pattern)) { $pattern.Toggle(); return }
        throw "Native control not invokable: $id"
    }
    function Capture([string]$name) {
        Start-Sleep -Milliseconds 900
        $rect = [NextCaptureNative+RECT]::new()
        if ([NextCaptureNative]::DwmGetWindowAttribute($hwnd,9,[ref]$rect,16) -ne 0) { [NextCaptureNative]::GetWindowRect($hwnd,[ref]$rect) | Out-Null }
        $bitmap = [Drawing.Bitmap]::new($rect.Right-$rect.Left,$rect.Bottom-$rect.Top)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try { $graphics.CopyFromScreen($rect.Left,$rect.Top,0,0,$bitmap.Size); $bitmap.Save("$Output/$name.png",[Drawing.Imaging.ImageFormat]::Png) }
        finally { $graphics.Dispose(); $bitmap.Dispose() }
    }
    Start-Sleep -Seconds 1
    Click-Control 'NavChats'
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $list = Find-Control 'ConversationList'
        $seeded = if ($null -ne $list) { $list.FindFirst([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.AndCondition]::new(
            [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ControlTypeProperty,[System.Windows.Automation.ControlType]::ListItem),
            [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty,'林间 · 设计'))) } else { $null }
        if ($null -eq $seeded) { Start-Sleep -Milliseconds 300 }
    } while ($null -eq $seeded -and [DateTime]::UtcNow -lt $readyDeadline)
    if ($null -eq $seeded) { throw 'Seeded conversation did not load through the real bridge' }
    $seeded.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
    Capture 'conversations'
    Click-Control 'NavNearby'; Capture 'nearby'
    Click-Control 'NavSettings'; Capture 'settings'
    $dark = Find-Control 'ThemeDark'
    if ($null -ne $dark) { Click-Control 'ThemeDark'; Click-Control 'NavChats'; Capture 'dark' }
    $controls = @()
    foreach ($element in $window.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition)) {
        try { $controls += @{name=$element.Current.Name;id=$element.Current.AutomationId;type=$element.Current.ControlType.ProgrammaticName} } catch { }
    }
    $controls | ConvertTo-Json -Depth 5 | Set-Content "$Output/automation.json" -Encoding UTF8
    & "$PSScriptRoot/test-native-ui.ps1" -Package $Package -Output $Output -Window $window -WindowHandle $hwnd
    if (Select-String -Path "$Output/startup.txt" -Pattern 'Unhandled:|Startup failed:' -Quiet) { throw 'Native window reported an unhandled runtime error' }
    @{kind='Actual WinUI 3 window on GitHub Windows runner';fixture='isolated seeded DesktopStore; not physical wireless acceptance';source=(Get-Content "$Package/build-info.json" -Raw | ConvertFrom-Json).source;captureSource=(git rev-parse HEAD);profileIsTemporary=$true;app=$exe;utc=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content "$Output/manifest.json" -Encoding UTF8
} catch {
    if ($hwnd -ne 0 -and $null -ne $hwnd) { try { Capture 'failure' } catch { } }
    if ($app.HasExited) {
        try {
            Add-Type -Path "$PSScriptRoot/NativeStartupProbe.cs"
            [NativeStartupProbe]::Run($exe, $Package, "$Output/native-debug.txt")
            Get-Content "$Output/native-debug.txt" | Select-Object -Last 120 | Write-Output
        } catch { Write-Output "Native probe failed: $_" }
    }
    foreach($file in @("$Output/startup.txt","$Output/stderr.txt","$Output/stdout.txt")) { if(Test-Path $file){ Get-Content $file -ErrorAction SilentlyContinue | Select-Object -Last 80 | Write-Output } }
    try { Get-WinEvent -FilterHashtable @{LogName='Application';StartTime=(Get-Date).AddMinutes(-5)} -ErrorAction Stop | Where-Object { $_.Message -match 'WindowsNext' } | Select-Object -First 4 -ExpandProperty Message | Write-Output } catch { }
    throw
} finally {
    if (-not $app.HasExited) { $app.CloseMainWindow() | Out-Null; if (-not $app.WaitForExit(8000)) { Stop-Process -Id $app.Id -Force } }
    Remove-Item Env:WOZAI_NEXT_DATA_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:WOZAI_NEXT_STARTUP_LOG -ErrorAction SilentlyContinue
}
