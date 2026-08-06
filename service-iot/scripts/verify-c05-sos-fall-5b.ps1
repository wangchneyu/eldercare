param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$ElderId = "1002",

    [string]$MavenCommand = "mvn"
)

if ($NameServer -match "127\.|localhost") {
    throw "C05 SOS/FALL verification must target the shared RocketMQ NameServer (e.g. 100.64.0.3:9876)."
}

& cmd /c "$MavenCommand -pl service-iot -am -Dtest=C05SosElderAndFall5bIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Diot.rocketmq.5b.sos-fall.enabled=true -Diot.rocketmq.5b.sos-fall.elder-id=$ElderId -Drocketmq.name-server=$NameServer test" 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "C05 SOS with ELDER binding / FALL 5B verification failed."
}

Write-Host "C05-01 (SOS with ELDER binding) and C05-02 (FALL) 5B verification passed."
Write-Host "Hand the logged eventId/traceId/sourceMessageId to alert-service (Sun Jie) to confirm"
Write-Host "only one alert per eventId with correct ELDER / LOCATION snapshot fields."
