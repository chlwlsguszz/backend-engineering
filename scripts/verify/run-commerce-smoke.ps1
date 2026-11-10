# Start a separate Docker network, database and index for outage and cache verification.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'commerce-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$composeArgs = @('compose', '-p', $project, '-f', (Join-Path $repo 'docker-compose.yml'),
    '-f', (Join-Path $PSScriptRoot 'compose.smoke.yml'))
$oldCache = $env:MARKETENGINE_CACHE_REDIS_ENABLED

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
try {
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
    Wait-For 'ES stock and tombstone after recovery' {
        (Invoke-RestMethod "$es/products/_doc/$($product.id)")._source.stockQuantity -eq 7 -and
        (Invoke-RestMethod "$es/products/_doc/$($other.id)")._source.outboxDeleted -eq $true
    }
    Send-Api DELETE "orders/$($order.id)" $null | Out-Null
    Send-Api DELETE "orders/$($order.id)" $null | Out-Null
    $replay = Send-Api POST orders @{ memberId=1; productId=$product.id; quantity=2; idempotencyKey='smoke-replay' }
    if ($replay.id -ne $order.id -or $replay.status -ne 'CANCELLED') { throw 'Cancellation replay failed' }
    Wait-For 'restored ES stock' { (Invoke-RestMethod "$es/products/_doc/$($product.id)")._source.stockQuantity -eq 10 }
    Wait-For 'drained outbox' { (Get-Metrics) -match '(?m)^product_outbox_pending(?:\{[^}]*\})?\s+0(?:\.0)?\s*$' }
    (Get-Metrics) -split "`n" | Where-Object { $_ -match '^product_outbox_' }

    $env:MARKETENGINE_CACHE_REDIS_ENABLED = 'false'
    Invoke-Compose @('up','-d','--no-deps','backend')
    $api = Get-Endpoint backend 8080
    Wait-For 'backend without feed cache' { (Invoke-RestMethod "$api/actuator/health").status -eq 'UP' }
    Write-Host 'Redis OFF'
    Read-Catalog $false
    Write-Host 'Commerce smoke passed.'
} catch {
    Invoke-Compose @('logs','--tail','30','backend')
    throw
} finally {
    $env:MARKETENGINE_CACHE_REDIS_ENABLED = $oldCache
    # $project is generated above, including its volumes and network.
    Invoke-Compose @('down','-v','--rmi','local','--remove-orphans')
}
