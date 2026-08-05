param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$MavenCommand = "mvn"
)

$ErrorActionPreference = "Stop"

& $MavenCommand -pl service-iot -am `
    "-Dtest=MqttRocketMq5bIntegrationTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" `
    "-Diot.mqtt.rocketmq.5b.enabled=true" `
    "-Drocketmq.name-server=$NameServer" `
    test

if ($LASTEXITCODE -ne 0) {
    throw "MQTT -> IoT -> RocketMQ continuous 5B verification failed."
}

Write-Host "MQTT -> IoT -> RocketMQ continuous 5B verification passed."
