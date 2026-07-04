# kc-nats-listener

[![Build](https://github.com/altessa-s/kc-nats-listener/actions/workflows/build.yml/badge.svg)](https://github.com/altessa-s/kc-nats-listener/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)
[![Keycloak 26.x](https://img.shields.io/badge/Keycloak-26.x-blue)](https://www.keycloak.org/)

A Keycloak event listener that publishes admin and client events to [NATS JetStream](https://docs.nats.io/nats-concepts/jetstream). Includes automatic reconnection, user data enrichment for client events, and graceful degradation when NATS is unavailable.

## Features

- Publishes Keycloak admin and client events to NATS JetStream
- Automatic reconnection (built-in + background) with configurable retry policy
- User data enrichment for client events (profile + custom attributes)
- Configurable JetStream streams: retention, storage, replicas, limits
- Environment variable–based configuration
- Graceful NOOP mode when NATS is unavailable
- Kubernetes-friendly: DNS re-resolution on reconnect, keepalive tuning
- Detailed connection state logging

## Requirements

- Keycloak 26.x
- Java 17+
- NATS server with JetStream enabled

## Quick Start

Build the shadow JAR, drop it into Keycloak's `providers/` directory, and rebuild:

```bash
./gradlew clean shadowJar
cp build/libs/kc-nats-listener-*.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
```

Or, in a Dockerfile:

```dockerfile
COPY --from=builder /app/build/libs/kc-nats-listener-*.jar /opt/keycloak/providers/
```

Point the listener at your NATS server:

```bash
KC_NATS_URL=nats://nats-server:4222
```

After restarting Keycloak, register the listener under **Realm Settings > Events > Config** by adding `kc-nats-listener` to the list of active event listeners.

For full installation steps and verification, see [Getting Started](docs/getting-started.md).

## Configuration

Configuration is done via environment variables or Keycloak SPI options (`--spi-events-listener--kc-nats-listener--<option>`). The only required setting is the NATS URL (`KC_NATS_URL`) — if it is unset, the adapter enters NOOP mode and silently drops every event.

| Variable                         | Description                                                        | Default |
|----------------------------------|--------------------------------------------------------------------|---------|
| `KC_NATS_URL`                    | NATS server URL; may include credentials. **Required** to publish. | —       |
| `KC_NATS_MAX_RECONNECTS`         | Max reconnection attempts (`0` = none, `-1` = unlimited)           | `-1`    |
| `KC_NATS_RECONNECT_WAIT_SECONDS` | Wait between reconnection attempts                                 | `2`     |
| `KC_NATS_CREATE_STREAMS`         | Create/update JetStream streams on startup                         | `false` |

For the complete list (keepalive, DNS behaviour, JetStream stream retention/discard policies), see [Configuration](docs/configuration.md).

### Running in Kubernetes

When a NATS pod is rescheduled it usually comes back with a new IP. The plugin already re-resolves DNS on every reconnect and retries forever, but the JVM DNS cache on the **Keycloak side** must also be tuned:

```bash
JAVA_OPTS_APPEND="-Dsun.net.inetaddr.ttl=30 -Dsun.net.inetaddr.negative.ttl=5"
```

See [Deployment Networking](docs/deployment-networking.md) for the full explanation and a hard-guarantee setup.

## Subjects

Events are published under fixed subject patterns:

| Event type   | Subject pattern                                                                  |
|--------------|----------------------------------------------------------------------------------|
| Admin event  | `KEYCLOAK.EVENTS.ADMIN.<REALM>.<SUCCESS\|ERROR>.<RESOURCE_TYPE>.<OPERATION>`     |
| Client event | `KEYCLOAK.EVENTS.CLIENT.<REALM>.<SUCCESS\|ERROR>.<CLIENT_ID>.<TYPE>`             |

All parts are uppercased; spaces are replaced with underscores.

## Documentation

| Guide                                      | Description                                                          |
|--------------------------------------------|----------------------------------------------------------------------|
| [Getting Started](docs/getting-started.md) | Build, install, register the listener, verify                        |
| [Architecture](docs/architecture.md)       | Connection state, event flow, user data enrichment, troubleshooting  |
| [Configuration](docs/configuration.md)     | All environment variables, JetStream stream config, examples         |
| [Deployment Networking](docs/deployment-networking.md) | Keycloak-side JVM DNS cache tuning for Kubernetes        |

## Contributing

Issues and pull requests are welcome. Please make sure the project builds (`./gradlew clean shadowJar`) before submitting a PR.

Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/) (`type(scope): description`); valid scopes are listed in [commit_scopes.txt](commit_scopes.txt). Enable the local validation hook once after cloning:

```bash
git config core.hooksPath .githooks
```

Commit messages and PR titles are also validated in CI.

## License

This project is licensed under the [MIT License](LICENSE).

Copyright 2026 ALTESSA SOLUTIONS INC.
