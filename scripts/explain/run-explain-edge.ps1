# Repo root에서 Postgres로 search_scenarios_typos_deep.sql 실행 (postgres 기동 후)
Set-Location (Resolve-Path "$PSScriptRoot\..\..")
Get-Content "$PSScriptRoot\search_scenarios_typos_deep.sql" -Raw | docker compose exec -T postgres psql -U marketengine -d marketengine -v ON_ERROR_STOP=1
