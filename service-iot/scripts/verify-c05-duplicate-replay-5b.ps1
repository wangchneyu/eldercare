param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$MavenCommand = "mvn"
)

$ErrorActionPreference = "Stop"

if ($NameServer -match "127\.|localhost") {
    throw "C05 duplicate replay verification must target the shared RocketMQ NameServer (e.g. 100.64.0.3:9876)."
}

& $MavenCommand -pl service-iot -am `
    "-Dtest=C05DuplicateReplay5bIntegrationTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" `
    "-Diot.rocketmq.5b.duplicate-replay.enabled=true" `
    "-Drocketmq.name-server=$NameServer" `
    test

if ($LASTEXITCODE -ne 0) {
    throw "C05 duplicate raw envelope replay 5B verification failed."
}

Write-Host "C05 duplicate raw envelope replay 5B verification passed."
Write-Host "Hand the logged eventId/traceId/sourceMessageId to alert-service (Sun Jie) to confirm only one alert per eventId."
