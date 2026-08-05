param(
    [Parameter(Mandatory = $true)]
    [string]$NameServer,

    [string]$FailureNameServer,

    [string]$MavenCommand = "mvn"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($FailureNameServer)) {
    $hostName = $NameServer -replace ":\d+$", ""
    $FailureNameServer = "${hostName}:9875"
}

if ($FailureNameServer -eq $NameServer -or $FailureNameServer -match ":9876$") {
    throw "FailureNameServer must be an intentionally unreachable non-production NameServer endpoint."
}

$probeId = "5B-C05-FAIL-" + [guid]::NewGuid().ToString("N")
$baseArguments = @("-pl", "service-iot", "-am", "-Dsurefire.failIfNoSpecifiedTests=false")

function Invoke-5bTest {
    param(
        [string]$TestClass,
        [string[]]$Properties
    )

    & $MavenCommand @baseArguments "-Dtest=$TestClass" @Properties test
    if ($LASTEXITCODE -ne 0) {
        throw "RocketMQ 5B verification failed in $TestClass."
    }
}

Write-Host "[1/3] Verify C04 and C05 producer sends against $NameServer"
Invoke-5bTest "RocketMq5bIntegrationTest" @(
    "-Diot.rocketmq.5b.enabled=true",
    "-Drocketmq.name-server=$NameServer"
)

Write-Host "[2/3] Create a controlled C05 failure with probe $probeId"
Invoke-5bTest "RocketMq5bFailureProbeIntegrationTest" @(
    "-Diot.rocketmq.5b.failure-probe.enabled=true",
    "-Diot.rocketmq.5b.probe-id=$probeId",
    "-Drocketmq.name-server=$FailureNameServer"
)

Write-Host "[3/3] Recover the same C05 Outbox record through $NameServer"
Invoke-5bTest "RocketMq5bRecoveryIntegrationTest" @(
    "-Diot.rocketmq.5b.recovery-probe.enabled=true",
    "-Diot.rocketmq.5b.probe-id=$probeId",
    "-Drocketmq.name-server=$NameServer"
)

Write-Host "RocketMQ 5B IoT -> Broker verification passed. Probe: $probeId"
