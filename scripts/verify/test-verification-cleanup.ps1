# Mock-only regression: this script never starts a native Docker process.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'stop-verification-containers.ps1'
$testProject = 'commerce-smoke-0123456789ab'
$firstId = 'a' * 64
$secondId = 'b' * 64
$mockState = [pscustomobject]@{
    Ids = @()
    Documents = @{}
    Calls = [Collections.Generic.List[object]]::new()
    FailCommand = $null
}

function docker {
    # A PowerShell function shadows docker.exe in the helper's child scope.
    $mockState.Calls.Add(@($args))
    Set-Variable -Name LASTEXITCODE -Value 0 -Scope 1
    if ($mockState.FailCommand -eq $args[0]) {
        Set-Variable -Name LASTEXITCODE -Value 1 -Scope 1
        return 'Simulated Docker failure'
    }
    switch -Exact ($args[0]) {
        'ps' {
            if (($args -join ' ') -ne "ps -aq --no-trunc --filter label=com.marketengine.verification=$testProject") {
                throw 'The helper did not scope its lookup to the verification token.'
            }
            return $mockState.Ids
        }
        'inspect' {
            if (-not $mockState.Documents.ContainsKey($args[1])) { throw 'Unexpected inspection target' }
            return ConvertTo-Json -Depth 6 -InputObject @($mockState.Documents[$args[1]]) -Compress
        }
        'stop' { return $args | Select-Object -Skip 3 }
        default { throw "Forbidden Docker command in cleanup: $($args -join ' ')" }
    }
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "FAILED: $Message" }
}
function Reset-Mock {
    $mockState.Ids = @()
    $mockState.Documents = @{}
    $mockState.Calls.Clear()
    $mockState.FailCommand = $null
}
function New-OwnedContainer([string] $Id, [string] $Service = 'backend') {
    return [pscustomobject]@{
        Id = $Id
        Name = "/$testProject-$Service-1"
        Config = [pscustomobject]@{ Labels = @{
            'com.docker.compose.project' = $testProject
            'com.marketengine.verification' = $testProject
        } }
    }
}
function Assert-Rejected([scriptblock] $Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected 'unsafe cleanup must be rejected'
    Assert-True (@($mockState.Calls | Where-Object { $_[0] -eq 'stop' }).Count -eq 0) 'rejected cleanup must not stop any container'
}

# Fail closed if a future helper bypasses the mock by using a native Docker path or Compose cleanup.
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$parseErrors)
Assert-True ($parseErrors.Count -eq 0) 'helper must parse'
$commands = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] }, $true))
foreach ($command in $commands) {
    $name = $command.GetCommandName()
    Assert-True ($name -in @('docker','Read-Docker','ForEach-Object','Where-Object','Select-Object',
            'ConvertFrom-Json','Out-Null','Write-Host')) 'helper must use only mocked Docker and the explicit safe command allowlist'
}
Assert-True ((Get-Command docker).CommandType -eq 'Function') 'Docker must be mocked before invoking the helper'

$oldProject = $env:COMPOSE_PROJECT_NAME
$oldJar = $env:BACKEND_TEST_JAR
try {
    $env:COMPOSE_PROJECT_NAME = 'marketengine-catalog-10m'
    $env:BACKEND_TEST_JAR = $null

    foreach ($invalid in @('marketengine-catalog-10m','commerce-smoke-invalid',"$testProject`n")) {
        Reset-Mock
        Assert-Rejected { & $helper -Project $invalid }
        Assert-True ($mockState.Calls.Count -eq 0) 'invalid projects must be rejected before any Docker call'
    }

    foreach ($mismatch in @('project','token','name','id')) {
        Reset-Mock
        $mockState.Ids = @($firstId,$secondId)
        $mockState.Documents[$firstId] = New-OwnedContainer $firstId
        $bad = New-OwnedContainer $secondId 'postgres'
        switch ($mismatch) {
            'project' { $bad.Config.Labels['com.docker.compose.project'] = 'marketengine-catalog-10m' }
            'token' { $bad.Config.Labels['com.marketengine.verification'] = 'commerce-smoke-fedcba987654' }
            'name' { $bad.Name = '/marketengine-catalog-10m-postgres-1' }
            'id' { $bad.Id = 'c' * 64 }
        }
        $mockState.Documents[$secondId] = $bad
        Assert-Rejected { & $helper -Project $testProject }
        Assert-True (@($mockState.Calls | Where-Object { $_[0] -eq 'inspect' }).Count -eq 2) 'validate every candidate before stopping the first'
    }

    Reset-Mock
    $mockState.Ids = @('unexpected output')
    Assert-Rejected { & $helper -Project $testProject }

    Reset-Mock
    $mockState.FailCommand = 'ps'
    Assert-Rejected { & $helper -Project $testProject }

    Reset-Mock
    $mockState.Ids = @($firstId,$secondId)
    $mockState.Documents[$firstId] = New-OwnedContainer $firstId
    $mockState.Documents[$secondId] = New-OwnedContainer $secondId 'postgres'
    & $helper -Project $testProject
    $stops = @($mockState.Calls | Where-Object { $_[0] -eq 'stop' })
    Assert-True ($stops.Count -eq 1) 'stop must be issued once for verified exact IDs'
    Assert-True (($stops[0] -join ' ') -eq "stop --time 10 $firstId $secondId") 'stop must contain only verified full IDs'
    Assert-True ($mockState.Calls[1][0] -eq 'inspect' -and $mockState.Calls[2][0] -eq 'inspect' -and $mockState.Calls[3][0] -eq 'stop') 'all inspection must precede stop'

    Reset-Mock
    & $helper -Project $testProject
    Assert-True ($mockState.Calls.Count -eq 1 -and $mockState.Calls[0][0] -eq 'ps') 'no matches must not invoke stop'

    Write-Host 'Cleanup guard passed: 11 mock-only cases; no native Docker commands executed.'
} finally {
    $env:COMPOSE_PROJECT_NAME = $oldProject
    $env:BACKEND_TEST_JAR = $oldJar
}
