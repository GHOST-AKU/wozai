param(
    [string]$Core = "$PSScriptRoot/mesh-lab.exe",
    [string]$State = "$env:LOCALAPPDATA/NearbyMeshLab/state",
    [switch]$Relay
)
$ErrorActionPreference = 'Stop'
# Run only from an owner-controlled parent; do not reuse NIM4 state or keys.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$sid = $identity.User
if (Test-Path -LiteralPath $State) {
    $entry = Get-Item -LiteralPath $State -Force
    if (-not $entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'State must be a real directory, not a reparse point.'
    }
    $acl = Get-Acl -LiteralPath $State
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value -or -not $acl.AreAccessRulesProtected) {
        throw 'Existing state must be owned by this user with protected access rules.'
    }
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -ne $sid.Value) {
            throw 'Existing state grants another principal access. Refusing to change its permissions.'
        }
    }
} else {
    $null = New-Item -ItemType Directory -Path $State
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $State -AclObject $acl
}
$arguments = @('run', '--state', $State, '--listen', '127.0.0.1:39770')
if ($Relay) { $arguments += '--relay' }
& $Core @arguments
exit $LASTEXITCODE
