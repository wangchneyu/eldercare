param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$MavenCommand = "mvn"
)

if ($NameServer -match "127\.|localhost") {
    throw "C05 hash-conflict verification must target the shared RocketMQ NameServer (e.g. 100.64.0.3:9876)."
}

& cmd /c "$MavenCommand -pl service-iot -am -Dtest=C05HashConflict5bIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Diot.rocketmq.5b.hash-conflict.enabled=true -Drocketmq.name-server=$NameServer test" 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "C05-05 same-eventId/different-payloadHash 5B verification failed."
}

Write-Host "C05-05 (same eventId, different payloadHash) 5B verification passed."
Write-Host "Hand the logged eventId and both payloadHash values to alert-service (Sun Jie) to confirm"
Write-Host "automatic handling is stopped and neither payload overwrites the other (manual review only)."