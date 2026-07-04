[← Architecture](architecture.md) · [Back to README](../README.md) · [Deployment Networking →](deployment-networking.md)

# Configuration

Every option can be set in two ways, in order of precedence:

1. **Keycloak SPI options** — `keycloak.conf` entries or CLI options in the form
   `--spi-events-listener--kc-nats-listener--<option>`. The option key is the environment
   variable name without the `KC_NATS_` prefix, lower-cased, with underscores replaced by
   dashes. For example, `KC_NATS_MAX_RECONNECTS` becomes
   `--spi-events-listener--kc-nats-listener--max-reconnects`.
2. **Environment variables** (`KC_NATS_*`) — used when the SPI option is not set.

The tables below document the environment variable names; the corresponding SPI option keys
are derived with the rule above.

## Basic Connection

| Variable                         | Type    | Description                                                          | Default         |
|----------------------------------|---------|----------------------------------------------------------------------|-----------------|
| `KC_NATS_URL`                    | string  | NATS server URL; may include credentials. **Required** to publish.   | None (required) |
| `KC_NATS_CREATE_STREAMS`         | boolean | Create/update JetStream streams on startup                           | `false`         |
| `KC_NATS_MAX_RECONNECTS`         | int     | Max reconnection attempts (`0` = none, `-1` = unlimited)             | `-1`            |
| `KC_NATS_RECONNECT_WAIT_SECONDS` | long    | Wait time in seconds between reconnection attempts                   | `2`             |
| `KC_NATS_PING_INTERVAL_SECONDS`  | long    | Keepalive ping interval; lower = faster dead-connection detection    | `30`            |
| `KC_NATS_NO_RESOLVE_HOSTNAMES`   | boolean | Re-resolve DNS on each (re)connect (K8s-friendly); `false` pins IPs  | `true`          |

> **Important:** If `KC_NATS_URL` is unset, the adapter runs in NOOP mode and silently drops every event. To publish, the variable **must** be set.

## Reconnection Behaviour

The adapter maintains an automatic reconnection loop in case of NATS connection loss:

- **Built-in reconnect** — driven by the NATS client library.
- **Background reconnect** — additional task that retries while the adapter is in NOOP mode after an initial failure.
- **Disable** — set `KC_NATS_MAX_RECONNECTS=0`.
- **Unlimited (default)** — `KC_NATS_MAX_RECONNECTS=-1`. Recommended for Kubernetes: a finite limit can be exhausted during a long outage (node drain / pod reschedule), after which the client gives up and the listener stays in NOOP mode until Keycloak restarts.

Connection state transitions are logged at INFO level: `CONNECTED`, `DISCONNECTED`, `RECONNECTED`, `RESUBSCRIBED`.

### Reconnection Scenarios

| Scenario                                  | Behaviour                                                                                                          |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| Connection lost during runtime            | Events buffered in 8 MB memory buffer; published once reconnected. Buffer overflow → events dropped.               |
| NATS unavailable at Keycloak startup      | Adapter enters NOOP mode; background task retries every `KC_NATS_RECONNECT_WAIT_SECONDS` until NATS comes online.  |
| NATS pod rescheduled (new IP) in K8s      | Client re-resolves the hostname on each reconnect (hostname resolution is deferred, not cached) and connects to the new IP. |

### Kubernetes / DNS caching

The client defers hostname resolution to each (re)connect instead of caching resolved IPs, so a rescheduled NATS pod with a new IP is picked up automatically. This still relies on the JVM DNS cache, which by default keeps successful lookups for 30 seconds. To shorten it, set on the **Keycloak** container:

```bash
JAVA_OPTS_APPEND="-Dsun.net.inetaddr.ttl=30 -Dsun.net.inetaddr.negative.ttl=5"
```

> `sun.net.inetaddr.ttl` is honoured only when the `networkaddress.cache.ttl` security property is not set in `java.security` (it is unset in the standard Keycloak image). For a hard guarantee, point `-Djava.security.properties=` at a file setting `networkaddress.cache.ttl=30`.

See [Deployment Networking](deployment-networking.md) for the full Keycloak-side setup.

## JetStream Streams

Streams are created/updated only when `KC_NATS_CREATE_STREAMS=true`. Otherwise, manage them externally (NATS CLI, Terraform, etc.).

Two streams are managed:

- **Admin stream** — fixed subject pattern `KEYCLOAK.EVENTS.ADMIN.>`
- **Client stream** — fixed subject pattern `KEYCLOAK.EVENTS.CLIENT.>`

### Admin Stream

| Variable                                          | Type   | Description                                            | Required |
|---------------------------------------------------|--------|--------------------------------------------------------|----------|
| `KC_NATS_ADMIN_STREAM_NAME`                       | string | Stream name                                            | Yes      |
| `KC_NATS_ADMIN_STREAM_MAX_BYTES`                  | long   | Maximum stream size in bytes                           | No       |
| `KC_NATS_ADMIN_STREAM_MAX_STREAM_AGE_SECONDS`     | long   | Maximum message age in seconds                         | No       |
| `KC_NATS_ADMIN_STREAM_STREAM_MAX_MSGS`            | long   | Maximum total messages                                 | No       |
| `KC_NATS_ADMIN_STREAM_MAX_MSGS_PER_SUBJECT`       | long   | Maximum messages per subject                           | No       |
| `KC_NATS_ADMIN_STREAM_STORAGE_TYPE`               | string | `FILE` or `MEMORY`                                     | No       |
| `KC_NATS_ADMIN_STREAM_RETENTION_POLICY`           | string | `LIMITS`, `INTEREST`, or `WORKQUEUE`                   | No       |
| `KC_NATS_ADMIN_STREAM_DISCARD_POLICY`             | string | `OLD` or `NEW`                                         | No       |
| `KC_NATS_ADMIN_STREAM_DUPLICATE_WINDOW_SECONDS`   | long   | Duplicate detection window in seconds                  | No       |
| `KC_NATS_ADMIN_STREAM_NUM_REPLICAS`               | int    | Number of replicas (clustered NATS)                    | No       |

### Client Stream

| Variable                                           | Type   | Description                                            | Required |
|----------------------------------------------------|--------|--------------------------------------------------------|----------|
| `KC_NATS_CLIENT_STREAM_NAME`                       | string | Stream name                                            | Yes      |
| `KC_NATS_CLIENT_STREAM_MAX_BYTES`                  | long   | Maximum stream size in bytes                           | No       |
| `KC_NATS_CLIENT_STREAM_MAX_STREAM_AGE_SECONDS`     | long   | Maximum message age in seconds                         | No       |
| `KC_NATS_CLIENT_STREAM_STREAM_MAX_MSGS`            | long   | Maximum total messages                                 | No       |
| `KC_NATS_CLIENT_STREAM_MAX_MSGS_PER_SUBJECT`       | long   | Maximum messages per subject                           | No       |
| `KC_NATS_CLIENT_STREAM_STORAGE_TYPE`               | string | `FILE` or `MEMORY`                                     | No       |
| `KC_NATS_CLIENT_STREAM_RETENTION_POLICY`           | string | `LIMITS`, `INTEREST`, or `WORKQUEUE`                   | No       |
| `KC_NATS_CLIENT_STREAM_DISCARD_POLICY`             | string | `OLD` or `NEW`                                         | No       |
| `KC_NATS_CLIENT_STREAM_DUPLICATE_WINDOW_SECONDS`   | long   | Duplicate detection window in seconds                  | No       |
| `KC_NATS_CLIENT_STREAM_NUM_REPLICAS`               | int    | Number of replicas (clustered NATS)                    | No       |

### Policy Reference

**Storage Type**

- `FILE` — persistent on-disk storage
- `MEMORY` — in-memory (faster, non-persistent)

**Retention Policy**

- `LIMITS` — retain until stream limits hit (size, count, or age)
- `INTEREST` — retain only while consumers are interested
- `WORKQUEUE` — retain until acknowledged by a consumer

**Discard Policy**

- `OLD` — drop oldest messages when full
- `NEW` — reject incoming messages when full

## Configuration Examples

### Minimal

```bash
# Connect to NATS without managing streams
KC_NATS_URL=nats://nats-server:4222
```

### Recommended Production

```bash
KC_NATS_URL=nats://nats-server:4222

# Unlimited reconnects with 5-second backoff
KC_NATS_MAX_RECONNECTS=-1
KC_NATS_RECONNECT_WAIT_SECONDS=5

# Streams managed externally (NATS CLI / Terraform)
KC_NATS_CREATE_STREAMS=false
```

### Full with Stream Creation

```bash
KC_NATS_URL=nats://nats-server:4222
KC_NATS_MAX_RECONNECTS=60
KC_NATS_RECONNECT_WAIT_SECONDS=2
KC_NATS_CREATE_STREAMS=true

# Admin stream
KC_NATS_ADMIN_STREAM_NAME=keycloak-admin-events
KC_NATS_ADMIN_STREAM_MAX_BYTES=10485760
KC_NATS_ADMIN_STREAM_MAX_STREAM_AGE_SECONDS=86400
KC_NATS_ADMIN_STREAM_STREAM_MAX_MSGS=10000
KC_NATS_ADMIN_STREAM_STORAGE_TYPE=FILE
KC_NATS_ADMIN_STREAM_RETENTION_POLICY=LIMITS
KC_NATS_ADMIN_STREAM_DISCARD_POLICY=OLD
KC_NATS_ADMIN_STREAM_NUM_REPLICAS=3

# Client stream
KC_NATS_CLIENT_STREAM_NAME=keycloak-client-events
KC_NATS_CLIENT_STREAM_MAX_BYTES=10485760
KC_NATS_CLIENT_STREAM_MAX_STREAM_AGE_SECONDS=86400
KC_NATS_CLIENT_STREAM_STREAM_MAX_MSGS=10000
KC_NATS_CLIENT_STREAM_STORAGE_TYPE=FILE
KC_NATS_CLIENT_STREAM_RETENTION_POLICY=LIMITS
KC_NATS_CLIENT_STREAM_DISCARD_POLICY=OLD
KC_NATS_CLIENT_STREAM_NUM_REPLICAS=3
```

## Subject Naming

Subjects are derived from event metadata and are always **UPPERCASE**. Spaces are replaced with underscores.

| Event type   | Subject pattern                                                                  | Example                                                                       |
|--------------|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| Admin event  | `KEYCLOAK.EVENTS.ADMIN.<REALM>.<SUCCESS\|ERROR>.<RESOURCE_TYPE>.<OPERATION>`     | `KEYCLOAK.EVENTS.ADMIN.MASTER.SUCCESS.USER.UPDATE`                            |
| Client event | `KEYCLOAK.EVENTS.CLIENT.<REALM>.<SUCCESS\|ERROR>.<CLIENT_ID>.<TYPE>`             | `KEYCLOAK.EVENTS.CLIENT.MASTER.SUCCESS.SECURITY-ADMIN-CONSOLE.REFRESH_TOKEN`  |

## See Also

- [Getting Started](getting-started.md) — installation and quick setup
- [Architecture](architecture.md) — connection states, event flow, payload structure
- [Deployment Networking](deployment-networking.md) — Keycloak-side JVM DNS cache tuning for Kubernetes
