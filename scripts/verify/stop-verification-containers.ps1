[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('\Acommerce-smoke-[0-9a-f]{12}\z')]
    [string] $Project
)

$ErrorActionPreference = 'Stop'

function Read-Docker([string[]] $Arguments) {
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $lines = & docker @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    if ($LASTEXITCODE -ne 0) { throw "Docker verification lookup failed: $($lines -join "`n")" }
    return $lines
}

# Never use Compose cleanup: a malformed config can fall back to a project from .env.
# No container, network, image or volume is deleted by this script.
$ids = @(Read-Docker @('ps','-aq','--no-trunc','--filter',"label=com.marketengine.verification=$Project") |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
foreach ($id in $ids) {
    if ($id -cnotmatch '\A[0-9a-f]{64}\z') {
        throw 'Refusing cleanup: Docker returned an invalid container ID.'
    }
    $inspected = @((Read-Docker @('inspect',$id)) -join "`n" | ConvertFrom-Json)
    if ($inspected.Count -ne 1) { throw "Refusing cleanup: expected one inspected container for $id." }
    $container = $inspected[0]
    if ($container.Id -cne $id -or
        $container.Config.Labels.'com.docker.compose.project' -cne $Project -or
        $container.Config.Labels.'com.marketengine.verification' -cne $Project -or
        [string]::IsNullOrWhiteSpace($container.Name) -or
        -not $container.Name.TrimStart('/').StartsWith("$Project-", [StringComparison]::Ordinal)) {
        throw "Refusing to stop container ${id}: verification ownership does not match."
    }
}
if ($ids.Count -gt 0) { Read-Docker (@('stop','--time','10') + $ids) | Out-Null }
Write-Host "Verification containers stopped; data volumes retained: $Project"
