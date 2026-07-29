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
| C04 retry retention | `IOT_VITAL_DELIVERY_TERMINAL_RETENTION_HOURS` | Default is 168 hours for terminal C04 failure-only records. |
| P0 retry controls | `IOT_OUTBOX_RETRY_*` | Tune only with an approved operational change. |

## Observability

`observability/prometheus-scrape.yml` and `observability/prometheus-rules.yml` are templates.
They need a real Prometheus target and Alertmanager receiver before C13 can be marked complete.
The rules do not create an alert delivery channel by themselves.
