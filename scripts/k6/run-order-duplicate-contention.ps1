# Run one contention workload and export the Hikari samples from its arrival window.
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateRange(0, 100)] [int] $DuplicatePairPct,
    [ValidateRange(1, 2147483647)] [int] $ProbeRps = 4,
    [ValidateRange(1, 2147483647)] [int] $PairRps = 8,
    [ValidateRange(1, 86400)] [int] $Seconds = 20,
    [ValidateRange(1, 1000)] [int] $HotCount = 10,
    [ValidateRange(1, 2147483647)] [int] $PreAllocatedVUs = 10,
    [ValidateRange(1, 2147483647)] [int] $MaxVUs = 100,
    [string] $ProjectName = $env:COMPOSE_PROJECT_NAME,
    [string] $TargetsFile = (Join-Path $PSScriptRoot 'order-targets.json')
)

$ErrorActionPreference = 'Stop'
if (-not $ProjectName) { $ProjectName = 'marketengine-catalog-10m' }
if ($MaxVUs -lt $PreAllocatedVUs) { throw 'MaxVUs must be at least PreAllocatedVUs.' }
$manifest = Get-Item -LiteralPath $TargetsFile
$docker = Get-Command docker.exe -CommandType Application | Select-Object -First 1 -ExpandProperty Path
$binding = & $docker compose -f (Join-Path $PSScriptRoot '..\..\docker-compose.yml') -p $ProjectName port prometheus 9090
if ($LASTEXITCODE -ne 0 -or ($binding | Select-Object -First 1) -notmatch ':(\d+)$') {
    throw "Could not resolve the published Prometheus port for $ProjectName."
}
$prometheusUrl = "http://localhost:$($Matches[1])"
$target = 'job="backend",instance="backend:8080"'

function Get-PrometheusSeries {
    param([string] $Query, [string] $At)
    $uri = "$prometheusUrl/api/v1/query?query=" + [uri]::EscapeDataString($Query)
    if ($At) { $uri += '&time=' + [uri]::EscapeDataString($At) }
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 10
    if ($response.status -ne 'success') { throw "Prometheus query failed: $Query" }
    $response.data.result
}

function Invoke-OrderLoad {
    param([string[]] $Arguments, [string] $LogPath)
    # Windows PowerShell 5.1 wraps native stderr. Use the actual k6 exit status.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    & $docker @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $LogPath -ErrorAction Stop | Out-Host
    return $LASTEXITCODE
}

$deadline = [DateTime]::UtcNow.AddSeconds(25)
do {
    try {
        $ready = @(Get-PrometheusSeries "hikaricp_connections_pending{$target} and on(job,instance) (up{$target} == 1)")
        if ($ready.Count) { break }
    } catch {
        Write-Verbose $_.Exception.Message
    }
    if ([DateTime]::UtcNow -ge $deadline) { throw "Check the backend target and Hikari metrics at $prometheusUrl/targets." }
    Start-Sleep -Seconds 2
} while ($true)

$results = Join-Path $env:TEMP ("k6-order-contention-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + "-$PID-pct$DuplicatePairPct")
New-Item -ItemType Directory -Path $results | Out-Null
$logPath = Join-Path $results 'k6.log'
Write-Host "Results: $results"
[ordered]@{
    Project = $ProjectName; DuplicatePairPct = $DuplicatePairPct; ProbeRps = $ProbeRps; PairRps = $PairRps
    Seconds = $Seconds; HotCount = $HotCount; PreAllocatedVUs = $PreAllocatedVUs; MaxVUs = $MaxVUs
    TargetsSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifest.FullName).Hash
    GitCommit = (git -C $PSScriptRoot rev-parse HEAD)
    Dirty = [bool](git -C $PSScriptRoot status --porcelain)
    BackendImage = (& $docker compose -p $ProjectName -f (Join-Path $PSScriptRoot '..\..\docker-compose.yml') images -q backend)
    Seed = (Get-Content -Raw -LiteralPath $manifest.FullName | ConvertFrom-Json).seed
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $results 'settings.json') -Encoding utf8

$runner = @('run', '--rm', '--network', "${ProjectName}_default",
    '-v', "${PSScriptRoot}:/scripts:ro", '-v', "${results}:/results",
    '--mount', "type=bind,source=$($manifest.FullName),target=/inputs/order-targets.json,readonly",
    'grafana/k6:0.58.0', 'run', '--quiet', '--summary-export=/results/k6-summary.json',
    '-e', 'TARGETS_FILE=/inputs/order-targets.json',
    '-e', 'BASE_URL=http://backend:8080', '-e', "DUPLICATE_PAIR_PCT=$DuplicatePairPct",
    '-e', "PROBE_RPS=$ProbeRps", '-e', "PAIR_RPS=$PairRps", '-e', "SECONDS=$Seconds",
    '-e', "HOT_COUNT=$HotCount", '-e', "PREALLOCATED_VUS=$PreAllocatedVUs", '-e', "MAX_VUS=$MaxVUs",
    '/scripts/order_duplicate_contention.js')
$k6Exit = Invoke-OrderLoad -Arguments $runner -LogPath $logPath
$windowStart = Select-String -LiteralPath $logPath -Pattern 'contention_window_start_ms=(\d+)' | Select-Object -First 1
if (-not $windowStart) { throw "No measured window found (k6 exit $k6Exit). See $logPath" }

$start = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$windowStart.Matches[0].Groups[1].Value)
$end = $start.AddSeconds($Seconds)
# A range vector gives the original scrape timestamps, excluding setup and teardown.
$series = @(Get-PrometheusSeries "{__name__=~`"hikaricp_connections_(pending|active|max)`",$target}[${Seconds}s]" $end.ToString('o'))
$samples = @(foreach ($metric in $series) {
    foreach ($sample in $metric.values) {
        [pscustomobject]@{
            UnixSeconds = $sample[0]; Pool = $metric.metric.pool
            Metric = $metric.metric.__name__; Value = [double]$sample[1]
        }
    }
})
$pending = @($samples | Where-Object Metric -eq 'hikaricp_connections_pending')
if (-not $pending.Count) { throw "No Hikari pending samples in the load window (k6 exit $k6Exit). See $results" }
$samples | Export-Csv -NoTypeInformation -Path (Join-Path $results 'hikari.csv')
foreach ($pool in ($pending | Group-Object Pool)) {
    $peak = ($pool.Group | Measure-Object Value -Maximum).Maximum
    Write-Host "Hikari $($pool.Name): peak waiting connections=$peak ($($pool.Count) scrapes)"
}
$run = Select-String -LiteralPath $logPath -Pattern 'run=([a-z0-9-]+) offered_post_rps=' | Select-Object -First 1
if (-not $run) { throw 'Missing order run ID' }
$prefix = 'contention-' + $run.Matches[0].Groups[1].Value + '-'
$opening = @(foreach ($line in (Select-String -LiteralPath $logPath -Pattern 'opening_inventory\[sku:live:required\]=([0-9,:]+)')) {
    foreach ($item in $line.Matches[0].Groups[1].Value.Split(',')) {
        $values = $item.Split(':')
        @{ id = [long]$values[0]; stock = [long]$values[1] }
    }
})
if (-not $opening.Count) { throw 'Missing opening inventory' }
# Pass the integer-only inventory as SQL before the fixed psql audit (works on Windows PowerShell 5.1 too).
$json = ConvertTo-Json -Compress -InputObject $opening
$auditSql = "\set opening '$json'" + [Environment]::NewLine + (Get-Content -Raw (Join-Path $PSScriptRoot '..\sql\check_order_run.sql'))
$auditJson = $auditSql | & $docker compose -p $ProjectName -f (Join-Path $PSScriptRoot '..\..\docker-compose.yml') exec -T postgres psql -X -qAt -U marketengine -d marketengine -v ON_ERROR_STOP=1 -v "prefix=$prefix"
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL reconciliation failed' }
$audit = ($auditJson -join '') | ConvertFrom-Json
$summary = (Get-Content -Raw (Join-Path $results 'k6-summary.json') | ConvertFrom-Json).metrics
$duplicatePosts = $summary.contention_duplicate_pairs.count
if ($null -eq $duplicatePosts) { throw 'Missing duplicate-pair count in the k6 summary.' }
$expected = ($ProbeRps + 2 * $PairRps) * $Seconds - $duplicatePosts
$audit | Add-Member NoteProperty runId $run.Matches[0].Groups[1].Value
$audit | Add-Member NoteProperty expectedOrders $expected
$audit | Add-Member NoteProperty committedOrdersPerSecond ($audit.orders / $Seconds)
$audit | ConvertTo-Json | Set-Content (Join-Path $results 'db-orders.json') -Encoding utf8
if ($k6Exit -ne 0 -or $audit.orders -ne $expected -or $audit.units -ne $expected -or
        $audit.mismatches -ne 0 -or $audit.checkedSkus -ne $opening.Count) {
    throw "Order/stock reconciliation failed or k6 exited with $k6Exit. See $results/db-orders.json and $logPath"
}
Write-Host "Committed orders: $($audit.orders), orders per second: $($audit.committedOrdersPerSecond), inventory mismatches: $($audit.mismatches)"
