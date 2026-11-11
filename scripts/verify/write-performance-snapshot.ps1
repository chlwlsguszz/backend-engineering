[CmdletBinding()]
param(
    [string] $Project = 'marketengine-catalog',
    [string] $OutputDirectory = (Join-Path $env:TEMP ('marketengine-metrics-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')))
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = @('compose', '-p', $Project, '-f', (Join-Path $repoRoot 'docker-compose.yml'))
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function Invoke-Docker([string[]] $Arguments) {
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $output = & docker @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments -join ' ')`n$($output -join "`n")" }
    return $output -join "`n"
}

function Get-ServiceUrl([string] $Service, [string] $Port) {
    $binding = Invoke-Docker ($compose + @('port', $Service, $Port))
    if ($binding -notmatch ':(\d+)\s*$') { throw "No published port for $Service" }
    return "http://127.0.0.1:$($Matches[1])"
}

$queries = @'
SELECT json_build_object(
    'capturedAt', clock_timestamp(),
    'tables', (SELECT json_agg(t) FROM (
        SELECT relname, n_live_tup, n_dead_tup, n_tup_upd, n_tup_hot_upd,
               last_autovacuum, last_autoanalyze, pg_total_relation_size(relid) AS total_bytes
        FROM pg_stat_user_tables WHERE relname IN ('products','orders','product_search_outbox')
    ) t),
    'waits', (SELECT json_agg(w) FROM (
        SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity
        WHERE datname = current_database() AND pid <> pg_backend_pid()
        GROUP BY wait_event_type, wait_event
    ) w),
    'statements', (SELECT json_agg(s) FROM (
        SELECT queryid, calls, round(total_exec_time::numeric, 2) AS total_exec_ms,
               shared_blks_hit, shared_blks_read, wal_bytes
        FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
        ORDER BY total_exec_time DESC LIMIT 15
    ) s)
);
'@
# Query IDs and counters are sufficient here; do not dump SQL literals or request data.
Invoke-Docker ($compose + @('exec','-T','postgres','psql','-U','marketengine','-d','marketengine','-At','-v','ON_ERROR_STOP=1','-c',$queries)) |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'postgres.json') -Encoding UTF8
foreach ($section in @('memory','stats','commandstats')) {
    Invoke-Docker ($compose + @('exec','-T','redis','redis-cli','INFO',$section)) |
        Set-Content -LiteralPath (Join-Path $OutputDirectory "redis-$section.txt") -Encoding UTF8
}
$es = Get-ServiceUrl elasticsearch 9200
Invoke-RestMethod -Uri "$es/_nodes/stats/jvm,thread_pool,indices,fs,http,process" -TimeoutSec 10 |
    ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'elasticsearch.json') -Encoding UTF8
$backend = Get-ServiceUrl backend 8080
$metrics = (Invoke-WebRequest -UseBasicParsing -Uri "$backend/actuator/prometheus" -TimeoutSec 10).Content
if ($metrics -is [byte[]]) { $metrics = [Text.Encoding]::UTF8.GetString($metrics) }
$metrics | Set-Content -LiteralPath (Join-Path $OutputDirectory 'backend.prom') -Encoding UTF8
$containers = (Invoke-Docker ($compose + @('ps','-q'))) -split '\r?\n' | Where-Object { $_ }
Invoke-Docker (@('stats','--no-stream','--format','{{json .}}') + $containers) |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'containers.jsonl') -Encoding UTF8
[ordered]@{ Project=$Project; CapturedAt=[DateTime]::UtcNow.ToString('o'); Commit=(& git -C $repoRoot rev-parse HEAD); Dirty=([bool](& git -C $repoRoot status --porcelain)) } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'run.json') -Encoding UTF8
Write-Host "Performance snapshot: $OutputDirectory"
