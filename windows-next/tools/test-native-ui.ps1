param([string]$Package, [string]$Output, $Window)
$ErrorActionPreference = 'Stop'
$temporaryRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$peerProfile = Join-Path $temporaryRoot ('wozai-next-ui-peer-' + [Guid]::NewGuid().ToString('N'))
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $Package 'kernel/runtime/bin/java.exe'))
$start.WorkingDirectory = $Package
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
$start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
foreach ($argument in @('-Djava.awt.headless=true','-cp',(Join-Path $Package 'kernel/lib/*'),'dev.ghost.wozai.next.BridgeMain','--profile',$peerProfile)) { $start.ArgumentList.Add($argument) }
$peer = [Diagnostics.Process]::Start($start)
$diagnostics = $peer.StandardError.ReadToEndAsync()
$script:sequence = 0
function Invoke-Peer([string]$Operation, $Arguments = @{}) {
    $id = 'ui-' + (++$script:sequence)
    $peer.StandardInput.WriteLine((@{id=$id;op=$Operation;args=$Arguments} | ConvertTo-Json -Compress -Depth 10))
    $peer.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $read = $peer.StandardOutput.ReadLineAsync()
        if (-not $read.Wait(30000)) { throw "Peer timed out: $Operation" }
        if ($null -eq $read.Result) { throw "Peer exited: $Operation" }
        $frame = $read.Result | ConvertFrom-Json
        if ($frame.id -eq $id) {
            if (-not $frame.ok) { throw "Peer command failed: $Operation ($($frame.error))" }
            return $frame.data
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Peer reply missing: $Operation"
}
function Find-Native([string]$Id) {
    $Window.FindFirst([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::AutomationIdProperty,$Id))
}
function Invoke-Native([string]$Id) {
    $control=Find-Native $Id
    if ($null -eq $control) { throw "Native UI missing: $Id" }
    $pattern=$null
    if ($control.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)) { $pattern.Invoke(); return }
    if ($control.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)) { $pattern.Select(); return }
    throw "Native UI cannot invoke: $Id"
}
try {
    Invoke-Peer 'settings' @{nickname='原生互通验收';language='zh-Hans'} | Out-Null
    $identity=Invoke-Peer 'snapshot'
    Invoke-Peer 'listen' @{mode='lan';enabled=$true} | Out-Null
    $listening=Invoke-Peer 'snapshot'
    if ($listening.endpoints.Count -eq 0) { throw 'Native UI acceptance requires a real private network interface' }
    Invoke-Native 'NavNearby'
    Start-Sleep -Milliseconds 400
    (Find-Native 'ConnectAddress').GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue([string]$listening.endpoints[0])
    Invoke-Native 'ConnectButton'
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do { $state=Invoke-Peer 'snapshot'; if ($null -eq $state.approval) { Start-Sleep -Milliseconds 200 } } while ($null -eq $state.approval -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $state.approval) { throw 'Native address connection did not reach explicit peer approval' }
    Invoke-Peer 'approve' @{token=$state.approval.token;remember=$false} | Out-Null
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do { $state=Invoke-Peer 'snapshot'; if ($state.phase -ne 'ready') { Start-Sleep -Milliseconds 200 } } while ($state.phase -ne 'ready' -and [DateTime]::UtcNow -lt $deadline)
    if ($state.phase -ne 'ready') { throw 'Native connection did not complete the real encrypted handshake' }
    Invoke-Peer 'select' @{peerId=$state.connectedPeerId} | Out-Null
    Invoke-Native 'NavChats'
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $contact=$Window.FindFirst([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.AndCondition]::new(
            [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty,'原生互通验收'),
            [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ControlTypeProperty,[System.Windows.Automation.ControlType]::ListItem)))
        if ($null -eq $contact) { Start-Sleep -Milliseconds 200 }
    } while ($null -eq $contact -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $contact) { throw 'Native conversation list did not show the authenticated peer' }
    $contact.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
    Start-Sleep -Milliseconds 600
    $sent='WinUI 原生窗口 → 原通信内核：发送验收'
    (Find-Native 'Composer').GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue($sent)
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do { $button=Find-Native 'SendButton'; if (-not $button.Current.IsEnabled) { Start-Sleep -Milliseconds 100 } } while (-not $button.Current.IsEnabled -and [DateTime]::UtcNow -lt $deadline)
    if (-not $button.Current.IsEnabled) { throw 'Native Send did not enable for the authenticated current conversation' }
    Invoke-Native 'SendButton'
    # Navigate immediately to exercise the in-flight send/draft boundary.
    Invoke-Native 'NavSettings'
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do { $received=Invoke-Peer 'snapshot'; $message=@($received.messages | Where-Object { $_.body -eq $sent }); if ($message.Count -eq 0) { Start-Sleep -Milliseconds 150 } } while ($message.Count -eq 0 -and [DateTime]::UtcNow -lt $deadline)
    if ($message.Count -ne 1 -or $message[0].outgoing) { throw 'Real peer did not durably receive exactly one native UI message' }
    Start-Sleep -Milliseconds 600
    Invoke-Native 'NavChats'
    Start-Sleep -Milliseconds 600
    $draft=(Find-Native 'Composer').GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).Current.Value
    if ($draft -ne '') { throw 'Successfully sent text reappeared in native draft after navigation' }
    $reply='原通信内核 → WinUI 原生窗口：接收验收通过。'
    Invoke-Peer 'send' @{peerId=$received.connectedPeerId;text=$reply} | Out-Null
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $incoming=$Window.FindFirst([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty,$reply))
        if ($null -eq $incoming) { Start-Sleep -Milliseconds 150 }
    } while ($null -eq $incoming -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $incoming) { throw 'Real incoming message was not rendered by the native window' }
    @{kind='WinUI UI Automation with two real unchanged kernels';source=(git rev-parse HEAD);addressConnect=$true;explicitPeerConsent=$true;encryptedHandshake=$true;nativeSend=$true;durablePeerReceive=$true;nativeReceive=$true;sendDraftNavigation=$true;physicalWireless=$false} | ConvertTo-Json | Set-Content "$Output/ui-smoke.json" -Encoding UTF8
    Write-Output 'Native UI smoke passed: address, consent, encrypted send/receive, and send/navigation draft persistence.'
} finally {
    $peer.StandardInput.Close()
    if (-not $peer.WaitForExit(8000)) { $peer.Kill($true) }
    $peer.Dispose()
}
