[CmdletBinding()]
param(
    [string]$MavenCommand = "mvn",
    [switch]$SkipAcl,
    [switch]$SkipFront,
    [switch]$StopDependencies
)

$ErrorActionPreference = "Stop"

$moduleRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleRoot
$workspaceRoot = Split-Path -Parent $repoRoot
$localCompose = Join-Path $moduleRoot "deploy/local/docker-compose.yml"
$secureCompose = Join-Path $moduleRoot "deploy/emqx/docker-compose.secure.yml"

function Invoke-Checked {
    param(
        [string]$Command,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $Command $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Wait-Healthy {
    param([string]$ContainerName)

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $status = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}starting{{end}}' $ContainerName 2>$null).Trim()
        if ($status -eq "healthy") {
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "Container did not become healthy: $ContainerName"
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not available. Start it before running local acceptance."
}

Invoke-Checked "docker" @("compose", "-f", $localCompose, "up", "-d") $repoRoot
Wait-Healthy "eldercare-redis-iot-local"
Wait-Healthy "eldercare-postgres-iot-local"
Wait-Healthy "eldercare-emqx-local"

if (-not $SkipAcl) {
    Invoke-Checked "docker" @("compose", "-f", $secureCompose, "up", "-d") $repoRoot
    Wait-Healthy "eldercare-emqx-secure-test"
}

Invoke-Checked $MavenCommand @("-pl", "service-iot", "-am", "test") $repoRoot
Invoke-Checked $MavenCommand @(
    "-pl", "service-iot", "-am",
    "-Dtest=MqttP0EmqxIntegrationTest",
    "-Diot.e2e.enabled=true",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "test"
) $repoRoot

if (-not $SkipAcl) {
    Invoke-Checked $MavenCommand @(
        "-pl", "service-iot", "-am",
        "-Dtest=MqttAclEmqxIntegrationTest",
        "-Diot.emqx.acl.enabled=true",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test"
    ) $repoRoot
}

$frontRoot = Join-Path $workspaceRoot "iot-front"
if (-not $SkipFront -and (Test-Path (Join-Path $frontRoot "package.json"))) {
    Invoke-Checked "npm" @("run", "lint") $frontRoot
    Invoke-Checked "npm" @("run", "build") $frontRoot
}

if ($StopDependencies) {
    Invoke-Checked "docker" @("compose", "-f", $localCompose, "down") $repoRoot
    if (-not $SkipAcl) {
        Invoke-Checked "docker" @("compose", "-f", $secureCompose, "down") $repoRoot
    }
}

Write-Host "Local IoT acceptance completed. Real RocketMQ, downstream consumers, and end-to-end P0 timing remain out of scope."
