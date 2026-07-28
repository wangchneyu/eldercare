# EMQX Security Fixture

This directory contains a local-only EMQX CE 5.8.8 authentication and Topic ACL fixture. It is separate from the normal anonymous development broker and listens on MQTT port `1885`.

Run it with:

```powershell
docker compose -f service-iot/deploy/emqx/docker-compose.secure.yml up -d
mvn -pl service-iot -am "-Dtest=MqttAclEmqxIntegrationTest" "-Diot.emqx.acl.enabled=true" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

The committed CSV passwords are test-only. For a real environment, replace the bootstrap source with a secret-mounted file or the EMQX management API, inject the `iot-service` credentials through `IOT_MQTT_USERNAME` and `IOT_MQTT_PASSWORD`, set a deployment-specific node cookie, and enable TLS. Do not expose the Dashboard publicly.

The EMQX built-in database imports bootstrap users only when the authenticator is created. To reseed this local fixture after changing its CSV, stop it with `docker compose -f service-iot/deploy/emqx/docker-compose.secure.yml down -v` and start it again. This removes only the fixture's named volumes.
