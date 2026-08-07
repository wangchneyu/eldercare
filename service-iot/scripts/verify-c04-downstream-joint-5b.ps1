param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$MavenCommand = "mvn"
)

if ($NameServer -match "127\.|localhost") {
    throw "C04 downstream joint verification must target the shared RocketMQ NameServer (e.g. 100.64.0.3:9876)."
}

& cmd /c "$MavenCommand -pl service-iot -am -Dtest=C04DownstreamJoint5bIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Diot.rocketmq.5b.c04.enabled=true -Drocketmq.name-server=$NameServer test" 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "C04-01/02/03 downstream joint 5B verification failed."
}

Write-Host "C04-01 (unique messageId mattress), C04-02 (same device+sourceMessageId replay), C04-03 (elder_id=null) 5B verification passed."
Write-Host "Hand the logged eventId/traceId/sourceMessageId/device_id to vital-sign-service (Zhou Guangzhao) to confirm"
Write-Host "correct persistence (elder_id string-or-null, data_time epoch ms, one record per sourceMessageId)."