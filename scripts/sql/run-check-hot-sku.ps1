$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

$sqlPath = Join-Path $PSScriptRoot "check_hot_sku_orders_and_stock.sql"
Get-Content $sqlPath -Raw | docker compose exec -T postgres psql -U marketengine -d marketengine -v ON_ERROR_STOP=1

