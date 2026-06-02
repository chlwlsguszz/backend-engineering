$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

$sqlPath = Join-Path $PSScriptRoot "seed_order_products_100.sql"
Get-Content $sqlPath -Raw | docker compose exec -T postgres psql -U marketengine -d marketengine -v ON_ERROR_STOP=1
