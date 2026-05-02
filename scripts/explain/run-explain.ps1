# Repo root에서 Postgres 컨테이너로 search_scenarios.sql 실행 (postgres 기동 후)
Set-Location (Resolve-Path "$PSScriptRoot\..\..")
Get-Content "$PSScriptRoot\search_scenarios.sql" -Raw | docker compose exec -T postgres psql -U marketengine -d marketengine -v ON_ERROR_STOP=1
