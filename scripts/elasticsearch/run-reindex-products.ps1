# Step 3: PostgreSQL products -> Elasticsearch bulk index.
# Requires: docker compose up -d postgres elasticsearch
#
#   .\scripts\elasticsearch\run-reindex-products.ps1
#   .\scripts\elasticsearch\run-reindex-products.ps1 -RecreateIndex
#
# On ~10M rows this can take a long time (tens of minutes depending on hardware).

param(
    [switch]$RecreateIndex
)

Set-Location (Resolve-Path "$PSScriptRoot\..\..")

Write-Host "Starting postgres + elasticsearch ..."
docker compose up -d postgres elasticsearch | Out-Null

$argsList = @("--spring.profiles.active=reindex")
if ($RecreateIndex) {
    $argsList += "--recreate-index"
}

Write-Host "Running bulk reindex (profile=reindex) ..."
Push-Location backend
try {
    & .\gradlew.bat bootRun --no-daemon --args="$($argsList -join ' ')"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "Verify document count:"
Invoke-RestMethod "http://localhost:9200/products/_count"
