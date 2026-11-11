# Rebuild the product search documents from the PostgreSQL catalog.
[CmdletBinding()]
param(
    [switch]$RecreateIndex,
    [ValidateRange(0, 8)]
    [Nullable[int]]$Workers,
    [ValidateRange(1, 2147483647)]
    [Nullable[int]]$BatchSize
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

function Invoke-ReindexStep {
    param([string]$FilePath, [string[]]$ArgumentList)

    $executablePath = Get-Command -Name $FilePath -CommandType Application -ErrorAction Stop |
        Select-Object -First 1 -ExpandProperty Path
    # Windows PowerShell 5.1 can turn redirected native stderr into error records.
    # Keep those diagnostics in the log; the process exit code determines success.
    $ErrorActionPreference = 'Continue'
    & $executablePath @ArgumentList 2>&1 | ForEach-Object { $_.ToString() }
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host "Starting postgres + elasticsearch ..."
$composeFile = Join-Path $repoRoot 'docker-compose.yml'
Invoke-ReindexStep 'docker.exe' @('compose', '-f', $composeFile, 'up', '-d', '--wait', 'postgres', 'elasticsearch')

# An existing schema-only database must retain its Flyway baseline location on every run.
$profiles = if (($env:SPRING_PROFILES_ACTIVE -split '\s*,\s*') -contains 'empty') { 'empty,reindex' } else { 'reindex' }
$argsList = @("--spring.profiles.active=$profiles")
if ($RecreateIndex) {
    $argsList += "--recreate-index"
}
if ($null -ne $Workers) {
    $argsList += "--marketengine.elasticsearch.reindex.workers=$Workers"
}
if ($null -ne $BatchSize) {
    $argsList += "--marketengine.elasticsearch.reindex.batch-size=$BatchSize"
}

Write-Host "Running bulk reindex (profile=reindex) ..."
Push-Location -LiteralPath (Join-Path $repoRoot 'backend')
try {
    Invoke-ReindexStep '.\gradlew.bat' @('bootRun', '--no-daemon', "--args=$($argsList -join ' ')")
}
finally {
    Pop-Location
}
exit 0
