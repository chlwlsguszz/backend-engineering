# Inspect the initialized catalog in the active Compose project.
$ErrorActionPreference = 'Stop'
$composeFile = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\docker-compose.yml')).Path
$query = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'check_catalog_distribution.sql')
$docker = Get-Command docker.exe -CommandType Application | Select-Object -First 1 -ExpandProperty Path
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

# Keep psql diagnostics visible even when the caller redirects stderr in Windows PowerShell.
$ErrorActionPreference = 'Continue'
$query | & $docker compose -f $composeFile exec -T postgres psql -X -P pager=off -v ON_ERROR_STOP=1 -U marketengine -d marketengine -f - 2>&1 |
    ForEach-Object { $_.ToString() }
exit $LASTEXITCODE
