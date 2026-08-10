param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$MavenCommand = "mvn"
)

$ErrorActionPreference = "Stop"

if ($NameServer.Trim() -match '^(?i:(localhost|127(?:\.\d{1,3}){3}|\[?::1\]?))(?::\d+)?$') {
    throw "C09 device-status 5B verification must target the shared RocketMQ NameServer (e.g. 100.64.0.3:9876)."
}

& $MavenCommand -pl service-iot -am `
    "-Dtest=C09DeviceStatus5bIntegrationTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" `
    "-Diot.rocketmq.5b.device-status.enabled=true" `
    "-Drocketmq.name-server=$NameServer" `
    test

if ($LASTEXITCODE -ne 0) {
    throw "C09 device-status ONLINE/OFFLINE/RECOVERED 5B verification failed."
}

Write-Host "C09 ONLINE/OFFLINE/RECOVERED device-status evidence collected on $NameServer (topic elder-device-status)."
