[CmdletBinding()]
param(
    [switch]$Stop
)

$ErrorActionPreference = "Stop"

$moduleRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleRoot
$composeFile = Join-Path $moduleRoot "deploy/local/docker-compose.rocketmq.yml"
$nameServerContainer = "eldercare-rocketmq-namesrv-local"
$brokerContainer = "eldercare-rocketmq-broker-local"
$topics = @(
    "elder-vital-raw",
    "elder-sos-event"
)

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Command $($Arguments -join ' ')"
    }
}

function Wait-Healthy {
    param([string]$ContainerName)

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $status = (& docker inspect --format '{{.State.Health.Status}}' $ContainerName 2>$null).Trim()
        if ($status -eq "healthy") {
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "Container did not become healthy: $ContainerName"
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not available. Start it before using the local RocketMQ fixture."
}

if ($Stop) {
    Invoke-Checked "docker" @("compose", "-f", $composeFile, "down")
    Write-Host "Local RocketMQ containers stopped. Named volumes were retained."
    exit 0
}

Push-Location $repoRoot
try {
    Invoke-Checked "docker" @("compose", "-f", $composeFile, "up", "-d")
}
finally {
    Pop-Location
}

Wait-Healthy $nameServerContainer
Wait-Healthy $brokerContainer

foreach ($topic in $topics) {
    Invoke-Checked "docker" @(
        "exec", $brokerContainer,
        "sh", "mqadmin", "updatetopic",
        "-n", "namesrv:9876",
        "-c", "DefaultCluster",
        "-t", $topic
    )
}

Write-Host "Local RocketMQ is ready at 127.0.0.1:9876."
Write-Host "Set ROCKETMQ_NAME_SERVER=127.0.0.1:9876 before starting iot-service."
Write-Host "Created topics: $($topics -join ', ')."
