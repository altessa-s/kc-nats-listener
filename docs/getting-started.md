[Back to README](../README.md) · [Architecture →](architecture.md)

# Getting Started

## Prerequisites

- **Java 17+** (build and runtime)
- **Gradle 8+** (or use the included `gradlew` wrapper)
- **Keycloak 26.x** (deployment target)
- **NATS server with JetStream enabled** (runtime dependency)

## Building

Clone the repository and build the shadow JAR:

```bash
git clone https://github.com/<your-org>/kc-nats-listener.git
cd kc-nats-listener
./gradlew clean shadowJar
```

The output JAR is located at `build/libs/kc-nats-listener-<version>.jar`.

## Installation

### Manual

Copy the shadow JAR into your Keycloak `providers/` directory and rebuild:

```bash
cp build/libs/kc-nats-listener-*.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
```

### Dockerfile

```dockerfile
COPY --from=builder /app/build/libs/kc-nats-listener-*.jar /opt/keycloak/providers/
```

## Verify Installation

After restarting Keycloak:

1. Log into the Admin Console.
2. Select the realm where you want to publish events.
3. Go to **Realm Settings > Events > Config**.
4. Add `kc-nats-listener` to the list of active **Event Listeners**.

> **Note:** Keycloak supports hot reload of providers, but a restart may be required if the JAR is not picked up.

## Quick Configuration

Set the NATS connection URL via environment variable:

```bash
KC_NATS_URL=nats://nats-server:4222
```

If `KC_NATS_URL` is **not set**, the adapter runs in NOOP mode and silently drops events. For full configuration details — including JetStream stream creation, reconnection settings, and stream policies — see [Configuration](configuration.md).

## See Also

- [Architecture](architecture.md) — connection state management, event flow, and data enrichment
- [Configuration](configuration.md) — all environment variables and JetStream stream settings
