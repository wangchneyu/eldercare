# service-iot Local Verification And Deployment Inputs

## Local Acceptance

The local fixture is for development verification only. It starts Redis, PostgreSQL, and an anonymous
EMQX CE 5.8.8 instance, then runs service tests, the opt-in MQTT-to-Outbox smoke tests, the
separate ACL fixture, and the front-end lint/build checks.

```powershell
cd D:\a康养项目\003\eldercare
.\service-iot\scripts\verify-local.ps1
```

Useful switches:

```powershell
# Keep the normal local verification but skip the isolated ACL fixture.
.\service-iot\scripts\verify-local.ps1 -SkipAcl

# Stop containers after verification. Named volumes are retained.
.\service-iot\scripts\verify-local.ps1 -StopDependencies
```

This verifies PostgreSQL, EMQX, MQTT ACK behavior, C04/C05 assets, Outbox retry behavior, and
the front-end build. It deliberately uses mocked RocketMQ in MQTT smoke tests. It is not proof
of real RocketMQ delivery, downstream consumption, or end-to-end P0 latency.

The local EMQX service also maps WebSocket MQTT from container port `8083` to host port `8084`.
This is for the `iot-front` browser simulator at `ws://localhost:8084/mqtt`; it must remain a
local development endpoint and is not a production MQTT exposure pattern.

For a local interactive `service-iot` run, activate the `dev` profile. It deliberately disables
Nacos discovery and config because this local Compose fixture does not start Nacos:

```powershell
cd D:\a康养项目\003\eldercare
mvn -pl service-iot spring-boot:run "-Dspring-boot.run.profiles=dev"
```

## Shared Gateway Integration

The Gateway public contract is `/api/iot/v1/**`; its `route-iot` rule rewrites that path to the
service route `/iot/**` and discovers `service-iot` through Nacos. To make this workstation
discoverable in the shared network, append the explicit `shared` profile to the local `dev`
profile. This retains the local PostgreSQL and EMQX configuration while enabling discovery:

```powershell
cd D:\a康养项目\003\eldercare
$env:NACOS_SERVER_ADDR = "100.64.0.3:8848"
$env:NACOS_DISCOVERY_IP = "100.64.0.12"
$env:ROCKETMQ_NAME_SERVER = "100.64.0.3:9876"
mvn -pl service-iot spring-boot:run "-Dspring-boot.run.profiles=dev,shared"
```

`NACOS_DISCOVERY_IP` must be the current Tailscale address of the machine running IoT. Confirm
the Nacos namespace, group, authentication requirement, and registration result with the Gateway
owner before treating the Gateway path as integrated. The shared profile enables discovery only;
it does not load unconfirmed Nacos configuration. Start the local PostgreSQL and EMQX containers
before this command, as required by the `dev` profile.

## Local RocketMQ Fixture

The separate local RocketMQ fixture is for producer and Outbox recovery verification on a Windows
development machine. It is not a shared C15 environment and must not be cited as downstream
consumer or end-to-end P0 evidence.

```powershell
cd D:\a康养项目\003\eldercare
.\service-iot\scripts\start-local-rocketmq.ps1
```

It starts a development-only single-master NameServer and Broker, creates `elder-vital-raw`,
`elder-sos-event`, and `elder-vital-delivery-failed`, and exposes the NameServer at
`127.0.0.1:9876`. `application-dev.yml` already uses that address; another environment only needs
to override `ROCKETMQ_NAME_SERVER`.

```powershell
# Stop containers but retain named volumes.
.\service-iot\scripts\start-local-rocketmq.ps1 -Stop
```

Before using it for an application run, make sure no other process occupies `9876`, `10909`,
`10911`, or `10912`. The Broker deliberately disables automatic topic creation, so the helper
script creates the three IoT Topics explicitly. To verify a P0 recovery manually, stop only the
Broker, publish an SOS, confirm the local Outbox stays `PENDING`, restart the Broker, and confirm
the same Outbox record reaches `SENT` with its original envelope.

## Runtime Inputs

| Input | Environment variable or configuration key | Notes |
|---|---|---|
| PostgreSQL URL | `SPRING_DATASOURCE_URL` | Managed through Nacos or the deployment environment. |
| PostgreSQL credentials | `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Never commit credentials. |
| MQTT broker URL | `MQTT_BROKER_URL` | Use `ssl://` in production after the TLS trust chain is supplied. |
| MQTT service credential | `MQTT_USERNAME`, `MQTT_PASSWORD` | Inject from a secret store; do not reuse the local ACL fixture accounts. |
| MQTT client ID | `MQTT_CLIENT_ID` | Must be unique per service instance. |
| RocketMQ NameServer | `ROCKETMQ_NAME_SERVER` | C15 input. Required for 5B only. |
| Nacos address | `NACOS_SERVER_ADDR` | Required outside the local test profile. |
| Nacos advertised IP | `NACOS_DISCOVERY_IP` | Set to the service host's reachable Tailscale IP for shared Gateway discovery. |
| C04 retry retention | `IOT_VITAL_DELIVERY_TERMINAL_RETENTION_HOURS` | Default is 168 hours for terminal C04 failure-only records. |
| P0 retry controls | `IOT_OUTBOX_RETRY_*` | Tune only with an approved operational change. |

## Observability

`observability/prometheus-scrape.yml` and `observability/prometheus-rules.yml` are templates.
They need a real Prometheus target and Alertmanager receiver before C13 can be marked complete.
The rules do not create an alert delivery channel by themselves.
