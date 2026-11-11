# Compare fixed-rate feed runs or collect one continuous arrival-rate sweep.
[CmdletBinding(DefaultParameterSetName = 'Fixed')]
param(
    [Parameter(ParameterSetName = 'Fixed')]
    [ValidateRange(1, 15000)]
    [int[]] $Rates = @(100, 250, 500, 1000, 2000, 4000, 7000, 10000),
    [Parameter(ParameterSetName = 'Fixed')]
    [ValidateRange(10, 5000)]
    [int] $VUs,
    [Parameter(Mandatory, ParameterSetName = 'Sweep')]
    [switch] $Sweep,
    [Parameter(ParameterSetName = 'Sweep')]
    [ValidateRange(1, 15000)]
    [int] $TargetRps = 10000,
    [Parameter(ParameterSetName = 'Sweep')]
    [ValidateRange(1, 15000)]
    [int] $BandRps = 1000,
    [Parameter(ParameterSetName = 'Sweep')]
    [ValidateRange(1, 5000)]
    [int] $InitialVUs = 100,
    [Parameter(ParameterSetName = 'Sweep')]
    [ValidateRange(10, 5000)]
    [int] $MaxVUs = 2000,
    [ValidateSet('Host', 'Direct')]
    [string] $Route = 'Host',
    [ValidateRange(0, 1800)]
    [int] $RampSeconds = 0,
    [Parameter(ParameterSetName = 'Sweep')]
    [ValidateRange(-1, 600)]
    [int] $HoldSeconds = -1,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string] $ComposeProject = 'marketengine-catalog-10m'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if ($Sweep) {
    if (-not $PSBoundParameters.ContainsKey('RampSeconds')) {
        $RampSeconds = if ($TargetRps -gt 10000) { 60 } else { 200 }
    }
    if (-not $PSBoundParameters.ContainsKey('Route')) { $Route = 'Direct' }
    if ($RampSeconds -lt 1 -or $InitialVUs -gt $MaxVUs) {
        throw 'Sweep requires a positive RampSeconds and InitialVUs <= MaxVUs.'
    }
    if ($TargetRps % $BandRps -ne 0 -or $TargetRps / $BandRps -gt 25) {
        throw 'TargetRps must be divisible by BandRps into 1 to 25 bands.'
    }
}
$docker = Get-Command docker.exe -CommandType Application | Select-Object -First 1 -ExpandProperty Path
$visibleProducts = '{"query":{"bool":{"must_not":[{"term":{"outboxDeleted":true}}]}}}'
$index = Invoke-RestMethod -Uri 'http://localhost:9200/products/_count' -Method Post -ContentType 'application/json' -Body $visibleProducts -TimeoutSec 15
$health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 15
if ($index.count -ne 10000000 -or $health.status -ne 'UP') {
    throw "Expected 10000000 searchable products and a healthy backend. Found count=$($index.count), health=$($health.status)."
}

$baseUrl = 'http://host.docker.internal:8080'
$networkArgs = @()
if ($Route -eq 'Direct') {
    # Use the backend service alias on the 10M catalog's dedicated Compose network.
    $networkArgs = @('--network', "${ComposeProject}_default")
    $baseUrl = 'http://backend:8080'
}

$results = Join-Path $env:TEMP ("k6-feed-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + "-$PID-$Route")
if ($Sweep) { $results += "-sweep" }
elseif ($RampSeconds -gt 0) { $results += "-ramp${RampSeconds}s" }
New-Item -ItemType Directory -Path $results | Out-Null
Write-Host "Route: $Route ($baseUrl)"
Write-Host "Results: $results"
$runner = @('run', '--rm') + $networkArgs + @(
    '-v', "${PSScriptRoot}:/scripts:ro", '-v', "${results}:/results",
    'grafana/k6:0.58.0', 'run', '--quiet',
    '-e', "BASE_URL=$baseUrl"
)

function Invoke-K6Stage {
    param([string] $Name, [string[]] $Arguments, [string] $Case = 'feed')

    $log = Join-Path $results "$Name.log"
    # Windows PowerShell 5.1 can wrap native stderr in ErrorRecord objects.
    # Keep warnings visible and use the Docker/k6 exit code to decide the outcome.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    & $docker @runner -e "CASE=$Case" @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $log -ErrorAction Stop
    $code = $LASTEXITCODE
    # A sweep can cross thresholds and still finish all bands and export its summary.
    if ($Case -eq 'feed-sweep' -and $code -eq 99) { return }
    if ($code -ne 0) {
        Write-Host "Stopped at $Name (exit $code). Log: $log"
        exit $code
    }
}

Write-Host 'Warming up: 50 RPS for 10s'
Invoke-K6Stage -Name 'warmup' -Arguments @('-e', 'FEED_WARMUP=1', '-e', 'RATE=50', '-e', 'DURATION=10s', '/scripts/search_scenarios.js')

if ($Sweep) {
    $holdSeconds = if ($HoldSeconds -ge 0) { $HoldSeconds } elseif ($TargetRps -gt 10000) { 0 } else { 30 }
    $rampMs = $RampSeconds * 1000
    # Both k6 and the report read these same time/rate endpoints from settings.json.
    $rampStages = @(
        if ($TargetRps -gt 10000) {
            [ordered]@{ TargetRps = 10000; EndMs = [int]($rampMs / 2) }
        }
        [ordered]@{ TargetRps = $TargetRps; EndMs = $rampMs }
    )
    $settings = [ordered]@{
        Route = $Route; BaseUrl = $baseUrl; TargetRps = $TargetRps; BandRps = $BandRps
        RampSeconds = $RampSeconds; RampStages = $rampStages; HoldSeconds = $holdSeconds
        InitialVUs = $InitialVUs; MaxVUs = $MaxVUs
        P99LimitMs = 200; FailureLimitPct = 0.1
        Commit = (& git -C $repoRoot rev-parse HEAD)
        Dirty = ([bool](& git -C $repoRoot status --porcelain))
        K6Image = 'grafana/k6:0.58.0'; ComposeProject = $ComposeProject
    }
    $settings | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $results 'settings.json') -Encoding UTF8
    $endpoints = $rampStages | ForEach-Object { "$($_.TargetRps) RPS at $($_.EndMs / 1000)s" }
    Write-Host "Sweep: 0 RPS -> $($endpoints -join ' -> '), hold ${holdSeconds}s; VUs $InitialVUs to $MaxVUs"
    $arguments = @(
        '-e', "RATE=$TargetRps", '-e', "BAND_RPS=$BandRps", '-e', "RAMP_SECONDS=$RampSeconds",
        '-e', "DURATION=${holdSeconds}s", '-e', "PREALLOCATED_VUS=$InitialVUs", '-e', "MAX_VUS=$MaxVUs",
        '-e', 'SWEEP_SETTINGS=/results/settings.json', '--summary-export=/results/feed-sweep.json', '/scripts/search_scenarios.js'
    )
    Invoke-K6Stage -Name 'feed-sweep' -Case 'feed-sweep' -Arguments $arguments
    $sweepExitCode = $LASTEXITCODE
    $report = Join-Path $PSScriptRoot 'write-feed-sweep-report.ps1'
    & $report -SummaryPath (Join-Path $results 'feed-sweep.json')
    Write-Host "Results: $results"
    exit $sweepExitCode
}

foreach ($rate in $Rates) {
    $stageVUs = if ($PSBoundParameters.ContainsKey('VUs')) { $VUs } else { [int][Math]::Max(30, [Math]::Ceiling($rate * 0.10)) }
    if ($RampSeconds -gt 0) {
        $startRate = [Math]::Min(50, $rate)
        Write-Host "Ramping: $startRate to $rate RPS over ${RampSeconds}s, then holding in the same k6 process"
    }
    Write-Host "Measuring: $rate RPS for 30s, $stageVUs VUs, p99 <= 200ms"
    $stage = @(
        '-e', "RATE=$rate", '-e', 'DURATION=30s', '-e', "PREALLOCATED_VUS=$stageVUs",
        '-e', "MAX_VUS=$stageVUs", '-e', 'P99_MS=200', '-e', "RAMP_SECONDS=$RampSeconds",
        "--summary-export=/results/feed-$rate.json", '/scripts/search_scenarios.js'
    )
    Invoke-K6Stage -Name "feed-$rate" -Arguments $stage
}
Write-Host "All requested stages passed. Results: $results"
