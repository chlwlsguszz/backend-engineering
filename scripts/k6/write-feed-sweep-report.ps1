# Interpret the legacy k6 --summary-export format. Each feed iteration sends one HTTP request.
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $SummaryPath,
    [ValidateRange(1, 86400)] [int] $RampSeconds = 200,
    [ValidateRange(1, 86400)] [int] $HoldSeconds = 30,
    [ValidateRange(1, 10000)] [int] $TargetRps = 10000,
    [ValidateRange(1, 10000)] [int] $BandRps = 1000,
    [ValidateRange(1, 60000)] [double] $P99LimitMs = 200,
    [ValidateRange(0, 100)] [double] $FailureLimitPct = 0.1
)

$ErrorActionPreference = 'Stop'
if ($TargetRps % $BandRps -ne 0) { throw 'TargetRps must be divisible by BandRps.' }
$source = Get-Item -LiteralPath $SummaryPath
$metrics = (Get-Content -Raw -Encoding UTF8 -LiteralPath $source.FullName | ConvertFrom-Json).metrics
if ($null -eq $metrics.'http_reqs{band:b0}' -and $null -eq $metrics.'http_reqs{band:hold}') {
    throw 'No tagged sweep metrics found. Use the JSON from a completed feed-sweep run.'
}

$bandCount = [int]($TargetRps / $BandRps)
$minimumSamples = 100
$rows = @(for ($i = 0; $i -le $bandCount; $i++) {
    if ($i -eq $bandCount) {
        $tag = 'hold'
        $lo = $hi = $TargetRps
        $label = "$TargetRps hold"
        $seconds = $HoldSeconds
    } else {
        $tag = "b$i"
        $lo = $i * $BandRps
        $hi = $lo + $BandRps
        $label = "$lo-$hi"
        $seconds = $RampSeconds / $bandCount
    }
    $plannedMean = ($lo + $hi) / 2
    $counter = $metrics."http_reqs{band:$tag}"
    $count = if ($null -ne $counter) { $counter.count }
    $latency = $metrics."http_req_duration{band:$tag}"
    $failures = $metrics."http_req_failed{band:$tag}"
    $checkFailures = $metrics."checks{band:$tag}".fails
    # k6 Rate.passes counts true samples. A true http_req_failed sample is an HTTP failure.
    $complete = $count -gt 0 -and $null -ne $latency.'p(95)' -and $null -ne $latency.'p(99)' -and
                $null -ne $failures.passes -and $null -ne $failures.fails -and
                ($failures.passes + $failures.fails) -eq $count
    $sampleState = if ($null -eq $count) { 'missing' } elseif ($count -eq 0) { 'empty' }
                   elseif (-not $complete) { 'incomplete' } elseif ($count -lt $minimumSamples) { 'small' }
                   else { 'observed' }
    $observedRps = if ($null -ne $count) { [Math]::Round($count / $seconds, 2) }
    $coveragePct = if ($null -ne $count) { [Math]::Round(100 * $count / ($seconds * $plannedMean), 2) }
    $p95 = if ($count -gt 0) { $latency.'p(95)' }
    $p99 = if ($count -gt 0) { $latency.'p(99)' }
    $failurePct = if ($complete) { 100.0 * $failures.passes / $count }
    [pscustomobject][ordered]@{
        Band = $label
        Seconds = $seconds
        TargetMeanRps = $plannedMean
        Requests = $count
        ObservedRps = $observedRps
        CoveragePct = $coveragePct
        P95Ms = $p95
        P99Ms = $p99
        HttpFailures = $failures.passes
        FailurePct = $failurePct
        CheckFailures = $checkFailures
        InitVUMax = $metrics."sweep_initialized_vus{band:$tag}".max
        ActiveVUMax = $metrics."sweep_active_vus{band:$tag}".max
        Samples = $sampleState
    }
})

$firstSlow = $null
$firstErrors = $null
$firstLatencyJump = $null
$firstErrorJump = $null
$previous = $null
foreach ($row in $rows) {
    if ($row.Samples -eq 'observed') {
        if (!$firstSlow -and $row.P99Ms -gt $P99LimitMs) { $firstSlow = $row.Band }
        if (!$firstErrors -and $row.FailurePct -gt $FailureLimitPct) { $firstErrors = $row.Band }
        if ($null -ne $previous -and $previous.Samples -eq 'observed') {
            if (!$firstLatencyJump -and $row.P99Ms -ge 2 * $previous.P99Ms -and
                ($row.P99Ms - $previous.P99Ms) -ge 20) { $firstLatencyJump = $row.Band }
            if (!$firstErrorJump -and ($row.FailurePct - $previous.FailurePct) -ge 0.5) {
                $firstErrorJump = $row.Band
            }
        }
    }
    $previous = $row
}

$csvPath = Join-Path $source.DirectoryName 'feed-sweep.csv'
$reportPath = Join-Path $source.DirectoryName 'feed-sweep-report.txt'
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath $csvPath
$table = ($rows | Format-Table Band, TargetMeanRps, ObservedRps, Requests, CoveragePct,
    @{Label='P95ms'; Expression={$_.P95Ms}; FormatString='F2'},
    @{Label='P99ms'; Expression={$_.P99Ms}; FormatString='F2'},
    HttpFailures, @{Label='Fail%'; Expression={$_.FailurePct}; FormatString='F3'},
    CheckFailures, InitVUMax, ActiveVUMax, Samples -AutoSize | Out-String -Width 220).TrimEnd()

function Format-Candidate([string] $Band) {
    if ($Band) { return $Band }
    return 'not observed in eligible bands'
}

$drops = if ($null -ne $metrics.dropped_iterations) { $metrics.dropped_iterations.count } else { 'unavailable' }
$report = @(
    $table
    ''
    "Dropped iterations across ramp and hold: $drops"
    "First band above p99 $P99LimitMs ms: $(Format-Candidate $firstSlow)"
    "First band above HTTP failure rate $FailureLimitPct%: $(Format-Candidate $firstErrors)"
    "First adjacent p99 rise (at least 2x and +20 ms): $(Format-Candidate $firstLatencyJump)"
    "First adjacent HTTP failure rise (at least +0.5 percentage points): $(Format-Candidate $firstErrorJump)"
    "Candidates use bands with at least $minimumSamples complete HTTP observations. See Samples for gaps."
    'ObservedRps = reported HTTP requests tagged at iteration start / band seconds. TargetMeanRps is the planned mean.'
    'Coverage compares those requests with the planned linear-ramp volume. Exact drops are available only for the full run.'
    'VU columns are sampled maxima. Confirm candidate RPS bands with fixed-rate runs before reporting a sustainable rate.'
    "CSV: $csvPath"
)
$report | Set-Content -LiteralPath $reportPath -Encoding UTF8
$report
