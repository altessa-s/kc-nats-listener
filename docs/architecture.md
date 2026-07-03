[← Getting Started](getting-started.md) · [Back to README](../README.md) · [Configuration →](configuration.md)

# Architecture

The adapter is a Keycloak `EventListenerProvider` that publishes admin and client events to NATS JetStream, with automatic reconnection and graceful degradation when NATS is unavailable.

## Components

```
io.altessa.keycloak.nats.listener/
├── Configuration.java                  # Env-var-driven configuration parsing
├── NATSEventListenerProviderFactory    # SPI entry point; manages connection lifecycle
├── NATSEventListenerProvider           # Publishes events when connection is healthy
└── NOOPEventListenerProvider           # No-op fallback when NATS is unavailable
```

## SPI Registration

The factory is registered via Java's `ServiceLoader`:

```
src/main/resources/META-INF/services/org.keycloak.events.EventListenerProviderFactory
```

## Connection State Management

The factory tracks one of three states and routes events accordingly:

| State             | Meaning                                                       | Behaviour                              |
|-------------------|---------------------------------------------------------------|----------------------------------------|
| `NOT_INITIALIZED` | Initial state before the first connection attempt            | Events dropped                          |
| `CONNECTED`       | Successfully connected to NATS JetStream                     | Events published                        |
| `FAILED`          | Connection attempt failed; adapter in NOOP mode              | Events dropped; background retry runs   |

When the adapter enters `FAILED` due to an initial connection failure (and `KC_NATS_MAX_RECONNECTS != 0`), a background reconnection task retries periodically until NATS becomes reachable.

## Event Flow

1. **Keycloak event** triggered by user action or admin operation.
2. **Event listener** receives the event from Keycloak.
3. **User data enrichment** *(client events only)* — loads user profile and attributes from the realm.
4. **Serialization** — event converted to JSON.
5. **Subject building** — subject derived from event metadata.
6. **NATS publish** — event sent to JetStream (if connection is healthy).

When the connection is unhealthy:

- **During initial connection failure** — events dropped (NOOP mode).
- **During runtime connection loss** — events buffered in 8 MB memory buffer; published on reconnect. Buffer overflow drops events.

## Event Data Enrichment

**Client events** are enriched with user data when a `userId` is present in the event. A `userRepresentation` JSON field is added containing:

- Profile data: `id`, `username`, `email`, `firstName`, `lastName`
- Account status: `emailVerified`, `enabled`, `createdTimestamp`
- Custom attributes (multi-valued attributes flattened to single string values)

This mirrors the `representation` field already present in **admin events**, providing a consistent shape across both event types.

**Admin events** are published as-is — the existing `representation` field is preserved.

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
  "userRepresentation": "{\"id\":\"db85bef5-...\",\"username\":\"johnny\",\"attributes\":{\"customField1\":\"value1\"}}"
}
```

## Troubleshooting

### Events not appearing in NATS

1. Verify `kc-nats-listener` is in **Realm Settings > Events > Config** as an active event listener.
2. Check Keycloak logs for connection state messages:
   ```
   INFO: NATS connection established to nats://localhost:4222
   INFO: Connection state changed from NOT_INITIALIZED to CONNECTED
   ```
3. If you see NOOP messages:
   ```
   ERROR: Failed to connect to NATS server
   INFO: Events will NOT be published to NATS. Provider will use NOOP mode.
   ```
   - Verify NATS is running and reachable.
   - Confirm `KC_NATS_URL` is **explicitly set** (not empty).
4. If you see `KC_NATS_URL not set`, the adapter is in NOOP mode by design — set the variable to enable publishing.

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
