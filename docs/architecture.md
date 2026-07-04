[← Getting Started](getting-started.md) · [Back to README](../README.md) · [Configuration →](configuration.md)

# Architecture

The adapter is a Keycloak `EventListenerProvider` that publishes admin and client events to NATS JetStream, with automatic reconnection and graceful degradation when NATS is unavailable.

## Components

```
io.altessa.keycloak.nats.listener/
├── Configuration.java                  # Option parsing (SPI scope + environment variables)
├── NatsEventListenerProviderFactory    # SPI entry point; manages connection lifecycle
├── NatsEventListenerProvider           # Publishes events when connection is healthy
└── NoopEventListenerProvider           # No-op fallback when NATS is unavailable
```

## SPI Registration

The factory is registered via Java's `ServiceLoader`:

```
src/main/resources/META-INF/services/org.keycloak.events.EventListenerProviderFactory
```

## Connection State Management

The factory owns a single NATS connection shared by all provider instances. While no connection
is available — the URL is not configured, or the initial connection has not succeeded yet — the
factory hands out a no-op provider and events are dropped. Once connected, it hands out the
publishing provider.

If the initial connection fails (and `KC_NATS_MAX_RECONNECTS != 0`), a background reconnection
task retries periodically until NATS becomes reachable. Reconnection of an already-established
connection is handled by the NATS client itself.

## Event Flow

1. **Keycloak event** triggered by user action or admin operation.
2. **Event listener** receives the event from Keycloak.
3. **User data enrichment** *(client events only)* — loads user profile and attributes from the realm.
4. **Serialization** — event converted to JSON.
5. **Subject building** — subject derived from event metadata.
6. **Transaction completion** — the publish is deferred until the Keycloak transaction commits;
   events from rolled-back transactions are never published.
7. **NATS publish** — event sent to JetStream asynchronously; the request thread does not wait
   for the JetStream acknowledgement.

Delivery is **at-most-once**: if a publish fails, the error is logged and the event is dropped.

When the connection is unhealthy:

- **During initial connection failure** — events dropped (NOOP mode).
- **During runtime connection loss** — events buffered in 8 MB memory buffer; published on reconnect. Buffer overflow drops events.

## Event Data Enrichment

**Client events** are enriched with user data when a `userId` is present in the event. A nested `userRepresentation` JSON object is added containing:

- Profile data: `id`, `username`, `email`, `firstName`, `lastName`
- Account status: `emailVerified`, `enabled`, `createdTimestamp`
- Custom attributes (multi-valued attributes flattened to single string values)

**Admin events** are published as-is — the existing `representation` field is preserved.

> **Privacy note:** enrichment publishes personal data (username, e-mail, names, attributes) to
> NATS. Configure stream retention and downstream consumers accordingly.

## Payload Examples

### Admin Event

Subject: `KEYCLOAK.EVENTS.ADMIN.EXAMPLE.SUCCESS.USER.UPDATE`

```json
{
  "id": "5ee1b9ee-426d-4f69-9877-b3a96b54da35",
  "time": 1628089918834,
  "realmId": "example",
  "authDetails": {
    "realmId": "master",
    "clientId": "56f3e1c8-1a94-4373-9173-90ed06ee9c83",
    "userId": "96087d0a-8334-4305-be07-72166ca937a6",
    "ipAddress": "172.30.0.1"
  },
  "resourceType": "USER",
  "operationType": "UPDATE",
  "resourcePath": "users/db85bef5-f1b8-462d-a563-7de86cf7a2da",
  "representation": "{\"id\":\"db85bef5-...\",\"username\":\"johnny\",\"email\":\"john@doe.com\"}",
  "error": null,
  "resourceTypeAsString": "USER"
}
```

### Client Event (with enrichment)

Subject: `KEYCLOAK.EVENTS.CLIENT.EXAMPLE.SUCCESS.ACCOUNT-CONSOLE.UPDATE_PASSWORD`

```json
{
  "id": "a1e97bf5-f1ac-477e-beb4-e8be6775f857",
  "time": 1628091019154,
  "type": "UPDATE_PASSWORD",
  "realmId": "example",
  "clientId": "account-console",
  "userId": "db85bef5-f1b8-462d-a563-7de86cf7a2da",
  "sessionId": null,
  "ipAddress": "172.30.0.1",
  "error": null,
  "details": {
    "auth_method": "openid-connect",
    "custom_required_action": "UPDATE_PASSWORD",
    "username": "johnny"
  },
  "userRepresentation": {
    "id": "db85bef5-f1b8-462d-a563-7de86cf7a2da",
    "username": "johnny",
    "email": "john@doe.com",
    "firstName": "John",
    "lastName": "Doe",
    "emailVerified": true,
    "enabled": true,
    "createdTimestamp": 1628089918000,
    "attributes": {
      "customField1": "value1"
    }
  }
}
```

## Troubleshooting

### Events not appearing in NATS

1. Verify `kc-nats-listener` is in **Realm Settings > Events > Config** as an active event listener.
2. Check Keycloak logs for connection messages:
   ```
   INFO: NATS connection established to nats://localhost:4222
   INFO: NATS event listener adapter initialized successfully
   ```
3. If you see connection failures:
   ```
   ERROR: Failed to connect to NATS server at nats://... Events will NOT be published until the connection is established.
   ```
   - Verify NATS is running and reachable.
   - Confirm `KC_NATS_URL` is **explicitly set** (not empty).
4. If you see `NATS URL is not configured`, the adapter is in NOOP mode by design — set `KC_NATS_URL` (or the corresponding SPI option) to enable publishing.

### Background reconnection not working

- Check `KC_NATS_MAX_RECONNECTS` is **not** `0`.
- Look for log lines: `Scheduling background reconnection attempts every X seconds` and `Attempting background reconnection to NATS server`.

### Streams not being created

- Confirm `KC_NATS_CREATE_STREAMS=true`.
- Confirm `KC_NATS_ADMIN_STREAM_NAME` and/or `KC_NATS_CLIENT_STREAM_NAME` are set.
- Look for JetStream API errors in Keycloak logs.

### User data missing from client events

- Enrichment runs only for **client events** with a non-null `userId`.
- If the user is not found in the realm, the event is published without enrichment.

### Connection keeps dropping

- Increase `KC_NATS_MAX_RECONNECTS` (or set to `-1` for unlimited).
- Increase `KC_NATS_RECONNECT_WAIT_SECONDS` to slow retry frequency.
- Inspect network stability and NATS server logs.

## See Also

- [Getting Started](getting-started.md) — installation and setup
- [Configuration](configuration.md) — all environment variables and JetStream settings
