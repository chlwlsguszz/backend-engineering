# Export existing catalog products for order load tests without changing database state.
[CmdletBinding()]
param(
    [int] $Seed = 42,
    [ValidateRange(1, 1000)]
    [int] $Count = 1000,
    [string] $OutputPath = (Join-Path $PSScriptRoot '..\k6\order-targets.json')
)

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '..\..\docker-compose.yml'
$sqlFile = Join-Path $PSScriptRoot 'seed_order_products_1000.sql'
$destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)

$rows = Get-Content -LiteralPath $sqlFile -Raw -Encoding UTF8 |
    docker compose -f $composeFile exec -T postgres psql -X -qAt -U marketengine -d marketengine -v ON_ERROR_STOP=1 -v "seed=$Seed" -v "sample_count=$Count"
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL target export failed (docker exit $LASTEXITCODE)."
}

$json = $rows -join [Environment]::NewLine
$manifest = ConvertFrom-Json -InputObject $json
if ($manifest.schemaVersion -ne 1 -or $manifest.seed -ne $Seed -or $manifest.requestedCount -ne $Count) {
    throw 'Unexpected order target manifest returned by PostgreSQL.'
}
if ($manifest.targets.Count -lt 1) {
    throw 'No active catalog products with positive stock were found.'
}

# Only publish a fully written manifest. Keep a previous export intact on failure.
$directory = [System.IO.Path]::GetDirectoryName($destination)
[void][System.IO.Directory]::CreateDirectory($directory)
$temporary = Join-Path $directory ([Guid]::NewGuid().ToString('N') + '.tmp')
try {
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine, $utf8)
    if ([System.IO.File]::Exists($destination)) {
        [System.IO.File]::Replace($temporary, $destination, [System.Management.Automation.Language.NullString]::Value)
    } else {
        [System.IO.File]::Move($temporary, $destination)
    }
} finally {
    [System.IO.File]::Delete($temporary)
}
Write-Host "Exported $($manifest.targets.Count) of $Count requested order targets to $destination (seed $Seed)."
