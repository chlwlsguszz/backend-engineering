# Repair the specific broken Windows Path entries that hide Docker's credential helper.
[CmdletBinding()]
param([switch] $MachineOnly)

$ErrorActionPreference = 'Stop'
$dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
$k6Bin = 'C:\Program Files\k6'
$keyName = 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment'
$rawOption = [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames

function Repair-PathValue([string] $Value) {
    $parts = $Value.Split(';')
    $fixed = for ($i = 0; $i -lt $parts.Length; $i++) {
        $entry = $parts[$i]
        if ($entry -ceq '"' -and $i + 1 -lt $parts.Length -and $parts[$i + 1] -ieq ($dockerBin + '"')) {
            continue
        }
        if ($entry -ieq ($dockerBin + '"') -or $entry -ieq ($k6Bin + '"')) {
            $entry.Substring(0, $entry.Length - 1)
        } else {
            $entry
        }
    }
    $fixed -join ';'
}

function Get-SystemPath {
    $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($keyName)
    try { $key.GetValue('Path', $null, $rawOption) } finally { $key.Dispose() }
}

function Update-SystemPath([string] $Original, [string] $Repaired) {
    $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($keyName, $true)
    try {
        if ($key.GetValue('Path', $null, $rawOption) -cne $Original) {
            throw 'System Path changed during repair. Close environment editors and retry.'
        }
        $key.SetValue('Path', $Repaired, $key.GetValueKind('Path'))
        if ($key.GetValue('Path', $null, $rawOption) -cne $Repaired) { throw 'System Path verification failed.' }
    } finally { $key.Dispose() }

    # Notify Explorer so newly started terminals can inherit the repaired Path.
    if (-not ('DockerPathRepair.EnvironmentChange' -as [type])) {
        Add-Type -Namespace DockerPathRepair -Name EnvironmentChange -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
public static extern System.IntPtr SendMessageTimeout(System.IntPtr window, uint message, System.UIntPtr wParam, string setting, uint flags, uint timeout, out System.UIntPtr result);
'@
    }
    $result = [UIntPtr]::Zero
    [void][DockerPathRepair.EnvironmentChange]::SendMessageTimeout([IntPtr]0xffff, 0x1a, [UIntPtr]::Zero, 'Environment', 2, 1000, [ref]$result)
}

$original = Get-SystemPath
$repaired = Repair-PathValue $original
if ($original -cne $repaired) {
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Update-SystemPath $original $repaired
    } elseif ($MachineOnly) {
        throw 'Administrator permission is required to repair System Path.'
    } else {
        Write-Host 'Approve the Windows administrator prompt to correct the malformed System Path entries.'
        $powershell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
        $arguments = '-NoProfile -NonInteractive -File "' + $PSCommandPath + '" -MachineOnly'
        $child = Start-Process -FilePath $powershell -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
        if ($child.ExitCode -ne 0) { throw "System Path repair failed (exit $($child.ExitCode))." }
        if ((Get-SystemPath) -cne $repaired) { throw 'The saved System Path differs from the expected repair.' }
    }
}
if ($MachineOnly) { return }

# A terminal opened before the repair still has its own copy of Path.
$env:Path = Repair-PathValue $env:Path
if (($env:Path -split ';') -notcontains $dockerBin) { $env:Path = "$dockerBin;$env:Path" }
Write-Host 'Checking the Node base image through Docker Buildx...'
& (Join-Path $dockerBin 'docker.exe') buildx imagetools inspect node:22-alpine --format '{{.Name}}'
if ($LASTEXITCODE -ne 0) { throw "Buildx registry check failed (exit $LASTEXITCODE)." }
Write-Host 'Docker registry check passed.'
