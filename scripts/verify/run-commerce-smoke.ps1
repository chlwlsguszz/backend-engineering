# Start a separate Docker network, database and index for outage and cache verification.
[CmdletBinding()]
param(
    [switch] $UseLocalJar,
    [string] $RuntimeImage = 'eclipse-temurin:21-jre',
    [switch] $Browser,
    [switch] $K6
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'commerce-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$composeArgs = @('compose', '-p', $project, '-f', (Join-Path $repo 'docker-compose.yml'),
    '-f', (Join-Path $PSScriptRoot 'compose.smoke.yml'))
$oldCache = $env:MARKETENGINE_CACHE_REDIS_ENABLED
$oldJar = $env:BACKEND_TEST_JAR
$oldImage = $env:BACKEND_TEST_IMAGE
$oldOrigin = $env:BACKEND_ORIGIN
$oldSmokeToken = $env:SMOKE_RUN_TOKEN
$oldComposeProject = $env:COMPOSE_PROJECT_NAME

function Invoke-Compose([string[]] $CliArgs) {
    $ErrorActionPreference = 'Continue'
    & docker @composeArgs @CliArgs
    if ($LASTEXITCODE -ne 0) { throw "Compose failed ($LASTEXITCODE): $CliArgs" }
}
function Get-Endpoint([string] $Service, [string] $Port) {
    $binding = Invoke-Compose @('port', $Service, $Port)
    if ($binding -notmatch ':(\d+)$') { throw "Unknown binding: $binding" }
    return "http://127.0.0.1:$($Matches[1])"
}
function Wait-For([string] $Description, [scriptblock] $Probe) {
    $until = [DateTime]::UtcNow.AddSeconds(100)
    $last = ''
    do {
        try { if (& $Probe) { return } } catch { $last = $_.Exception.Message }
        Start-Sleep -Milliseconds 800
    } while ([DateTime]::UtcNow -lt $until)
    throw "Timed out waiting for $Description. $last"
}
function Send-Api([string] $Method, [string] $Path, $Body) {
    $options = @{ Method = $Method; Uri = "$api/api/$Path"; TimeoutSec = 12; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $options.ContentType = 'application/json'
        $options.Body = ConvertTo-Json -Compress -InputObject $Body
    }
    (Invoke-RestMethod @options).data
}
function Get-Metrics {
    $reply = Invoke-WebRequest -UseBasicParsing -Uri "$api/actuator/prometheus" -TimeoutSec 8
    $content = $reply.Content
    if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
    return $content
}
function Read-Catalog([bool] $CacheEnabled) {
    $paths = @('products?size=12', 'products?size=12&category=TOP',
        'products?size=12&keyword=ME0000001', 'products?size=12&page=3')
    foreach ($path in $paths) {
        $before = Get-SearchCount
        $times = foreach ($sample in 1..21) {
            $timer = [Diagnostics.Stopwatch]::StartNew()
            $page = Send-Api GET $path $null
            if ($null -eq $page -or $null -eq $page.items) { throw "Missing product slice for $path" }
            $timer.Stop()
            $timer.Elapsed.TotalMilliseconds
        }
        $calls = (Get-SearchCount) - $before
        $warm = @($times | Select-Object -Skip 1 | Sort-Object)
        Write-Host ('{0} firstMs={1:N2} warmN=20 warmP95Ms={2:N2} warmP99Ms={3:N2} ESShardQueries={4}' -f $path,$times[0],$warm[18],$warm[19],$calls)
        # This isolated products index has a single primary shard.
        $expected = if ($CacheEnabled -and $path -notmatch 'page=3') { 1 } else { 21 }
        if ($calls -ne $expected) { throw "Unexpected ES query count for $path (expected $expected, actual $calls)" }
    }
    (Get-Metrics) -split "`n" | Where-Object { $_ -match '^(cache_gets|product_feed_|product_search_)' }
    Invoke-Compose @('exec','-T','redis','redis-cli','info','memory') | Select-String '^used_memory:'
    Invoke-Compose @('exec','-T','redis','redis-cli','info','stats') | Select-String '^evicted_keys:'
}
function Get-SearchCount {
    (Invoke-RestMethod "$es/products/_stats/search")._all.total.search.query_total
}
function Invoke-Frontend([string] $Task) {
    Push-Location (Join-Path $repo 'frontend')
    try {
        $ErrorActionPreference = 'Continue'
        $PSNativeCommandUseErrorActionPreference = $false
        & npm.cmd run $Task 2>&1 | ForEach-Object { $_.ToString() }
        if ($LASTEXITCODE -ne 0) { throw "Frontend $Task failed" }
    } finally { Pop-Location }
}
try {
    $env:SMOKE_RUN_TOKEN = $project
    $env:COMPOSE_PROJECT_NAME = $project
    if ($UseLocalJar) {
        $jars = @(Get-ChildItem -LiteralPath (Join-Path $repo 'backend/build/libs') -Filter '*.jar' |
            Where-Object { $_.Name -notlike '*-plain.jar' })
        if ($jars.Count -ne 1) { throw 'Build exactly one backend bootJar before using -UseLocalJar.' }
        $env:BACKEND_TEST_JAR = $jars[0].FullName
        $env:BACKEND_TEST_IMAGE = $RuntimeImage
        $composeArgs += @('-f', (Join-Path $PSScriptRoot 'compose.local-jar.yml'))
    }
    $env:MARKETENGINE_CACHE_REDIS_ENABLED = 'true'
    Invoke-Compose @('up','-d','--build','--wait','backend')
    $api = Get-Endpoint backend 8080
    $es = Get-Endpoint elasticsearch 9200
    Wait-For 'initial backend' { (Invoke-RestMethod "$api/actuator/health").status -eq 'UP' }
    Invoke-Compose @('stop','backend')
    Invoke-Compose @('run','--rm','--no-deps','-T','backend','--spring.profiles.active=reindex','--recreate-index')
    Invoke-Compose @('start','backend')
    $api = Get-Endpoint backend 8080
    Wait-For 'backend after offline reindex' { (Invoke-RestMethod "$api/actuator/health").status -eq 'UP' }
    Write-Host 'Redis ON (sample 1 cold for cached pages; next samples warm)'
    Read-Catalog $true

    Invoke-Compose @('stop','redis')
    Start-Sleep -Seconds 3 # Expire L1 so the check actually exercises the failed Redis lookup.
    $page = Send-Api GET 'products?size=12' $null
    if ($page.items.Count -ne 12) { throw 'Redis outage did not return a full search page' }
    Invoke-Compose @('start','redis')
    Invoke-Compose @('stop','elasticsearch')
    $sku = @{ name='Smoke SKU'; description='Verification'; category='TOP'; brand='Demo'; gender='UNISEX';
        color='BLACK'; status='ACTIVE'; stockQuantity=10; priceAmount=20; popularityScore=1 }
    $product = Send-Api POST products $sku
    $other = Send-Api POST products $sku
    $order = Send-Api POST orders @{ memberId=1; productId=$product.id; quantity=2; idempotencyKey='smoke-replay' }
    if ((Send-Api GET "products/$($product.id)" $null).stockQuantity -ne 8) { throw 'DB reservation failed' }
    Send-Api PUT "orders/$($order.id)" @{ status='CONFIRMED'; quantity=3 } | Out-Null
    Send-Api DELETE "products/$($other.id)" $null | Out-Null
    Wait-For 'observable ES outage backlog' { (Get-Metrics) -match '(?m)^product_outbox_pending(?:\{[^}]*\})?\s+2(?:\.0)?\s*$' }
    Invoke-Compose @('start','elasticsearch')
    $es = Get-Endpoint elasticsearch 9200
    Wait-For 'ES availability and tombstone after recovery' {
        (Invoke-RestMethod "$es/products/_doc/$($product.id)")._source.available -eq $true -and
        (Invoke-RestMethod "$es/products/_doc/$($other.id)")._source.outboxDeleted -eq $true
    }
    if ((Send-Api GET "products/$($product.id)" $null).stockQuantity -ne 7) { throw 'Exact detail stock was not preserved' }
    $lastOrder = Send-Api POST orders @{ memberId=1; productId=$product.id; quantity=7; idempotencyKey='smoke-last-units' }
    Wait-For 'sold out in search' { (Invoke-RestMethod "$es/products/_doc/$($product.id)")._source.available -eq $false }
    Send-Api DELETE "orders/$($lastOrder.id)" $null | Out-Null
    Wait-For 'available after restock' { (Invoke-RestMethod "$es/products/_doc/$($product.id)")._source.available -eq $true }
    Send-Api DELETE "orders/$($order.id)" $null | Out-Null
    Send-Api DELETE "orders/$($order.id)" $null | Out-Null
    $replay = Send-Api POST orders @{ memberId=1; productId=$product.id; quantity=2; idempotencyKey='smoke-replay' }
    if ($replay.id -ne $order.id -or $replay.status -ne 'CANCELLED') { throw 'Cancellation replay failed' }
    if ((Send-Api GET "products/$($product.id)" $null).stockQuantity -ne 10) { throw 'Cancellation did not restore exact DB stock' }
    Wait-For 'drained outbox' { (Get-Metrics) -match '(?m)^product_outbox_pending(?:\{[^}]*\})?\s+0(?:\.0)?\s*$' }
    (Get-Metrics) -split "`n" | Where-Object { $_ -match '^product_outbox_' }

    $env:MARKETENGINE_CACHE_REDIS_ENABLED = 'false'
    Invoke-Compose @('up','-d','--no-deps','backend')
    $api = Get-Endpoint backend 8080
    Wait-For 'backend without feed cache' { (Invoke-RestMethod "$api/actuator/health").status -eq 'UP' }
    Write-Host 'Redis OFF'
    Read-Catalog $false
    # Validate the optional PG indexes on this disposable 1,000-product database.
    $ErrorActionPreference = 'Continue'
    Get-Content -Raw -LiteralPath (Join-Path $repo 'scripts/sql/enable-postgres-search-indexes.sql') |
        & docker @composeArgs exec -T postgres psql -U marketengine -d marketengine -v ON_ERROR_STOP=1
    $indexExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($indexExit -ne 0) { throw 'Optional PostgreSQL search index verification failed' }
    if ($K6) {
        foreach ($case in @('pagination','feed','browse')) {
            $ErrorActionPreference = 'Continue'
            & docker run --rm --network "${project}_default" -v "${repo}/scripts/k6:/scripts:ro" grafana/k6:0.58.0 run --quiet `
                -e BASE_URL=http://backend:8080 -e "CASE=$case" -e RATE=5 -e DURATION=3s -e MAX_PAGE=3 /scripts/search_scenarios.js
            $k6Exit = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            if ($k6Exit -ne 0) { throw "k6 $case smoke failed ($k6Exit)" }
        }
    }
    if ($Browser) {
        $env:BACKEND_ORIGIN = $api
        Invoke-Frontend build
        Invoke-Frontend 'test:e2e'
    }
    & (Join-Path $PSScriptRoot 'write-performance-snapshot.ps1') -Project $project
    Write-Host 'Commerce smoke passed.'
} catch {
    Invoke-Compose @('logs','--tail','30','backend')
    throw
} finally {
    try {
        & (Join-Path $PSScriptRoot 'stop-verification-containers.ps1') -Project $project
    } finally {
        $env:MARKETENGINE_CACHE_REDIS_ENABLED = $oldCache
        $env:BACKEND_TEST_JAR = $oldJar
        $env:BACKEND_TEST_IMAGE = $oldImage
        $env:BACKEND_ORIGIN = $oldOrigin
        $env:SMOKE_RUN_TOKEN = $oldSmokeToken
        $env:COMPOSE_PROJECT_NAME = $oldComposeProject
    }
}
