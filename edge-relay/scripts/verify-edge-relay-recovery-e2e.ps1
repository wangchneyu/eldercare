Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$moduleRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $moduleRoot
$composeFile = Join-Path $moduleRoot 'deploy/docker-compose.yml'

Set-Location $repositoryRoot
docker compose -f $composeFile up -d --build --wait
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to start the local edge-relay verification stack.'
}

& mvn -pl edge-relay -am `
    '-Dedge.e2e.enabled=true' `
    '-Dtest=EdgeRelayRecoveryE2eTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' `
    test
if ($LASTEXITCODE -ne 0) {
    throw 'Edge relay recovery E2E verification failed.'
}
