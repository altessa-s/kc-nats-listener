[← Configuration](configuration.md) · [Back to README](../README.md)

# Keycloak Deployment Notes — DNS Caching & Connection Resilience

**Scope:** these settings are applied to the **Keycloak process itself** (env vars, JVM flags).
They are intentionally *not* shipped inside the plugin, because the plugin runs inside the
Keycloak JVM and cannot change JVM-level policy after startup.

---

## 1. Why this matters

In Kubernetes, a node restart or pod reschedule gives the rescheduled NATS pod a **new IP**.
The **JVM DNS cache** can then keep the listener talking to a **dead/stale address**:
successful name lookups are cached (default 30s, or "forever" if a
`SecurityManager`/`networkaddress.cache.ttl` is set), so the new IP is not seen until the
cache expires.

---

## 2. JVM DNS cache

Set on the Keycloak container/JVM:

```bash
JAVA_OPTS_APPEND="-Dsun.net.inetaddr.ttl=30 -Dsun.net.inetaddr.negative.ttl=5"
```

- `sun.net.inetaddr.ttl=30` — cache successful lookups for 30s instead of the default.
- `sun.net.inetaddr.negative.ttl=5` — cache failed lookups for only 5s (so a just-started pod is
  picked up quickly).

> **Caveat:** `-Dsun.net.inetaddr.ttl` is honoured **only** if the `networkaddress.cache.ttl`
> security property is *not* already set in `java.security` (it is unset in the standard Keycloak
> image). For a hard guarantee, mount a file and add:
>
> ```bash
> JAVA_OPTS_APPEND="$JAVA_OPTS_APPEND -Djava.security.properties=/opt/keycloak/extra.security"
> ```
>
> `/opt/keycloak/extra.security`:
> ```properties
> networkaddress.cache.ttl=30
> networkaddress.cache.negative.ttl=5
> ```

This **cannot** be done from inside a Keycloak SPI plugin: the JVM DNS cache policy is fixed at the
first name resolution during startup, long before any plugin loads. Calling
`Security.setProperty("networkaddress.cache.ttl", …)` from plugin code is a no-op.

---

## 3. What the `kc-nats-listener` plugin already handles itself

For completeness — you do **not** need to configure anything extra for the NATS path:

- The NATS client (jnats) uses a **single connection, no pool**, and detects a dead socket via its
  own **PING/PONG keepalive** (this plugin lowers `pingInterval` to ~30s), then reconnects.
- On every (re)connect it **re-resolves the hostname** (`noResolveHostnames()`), so a new NATS pod
  IP is picked up — again, bounded by the JVM DNS TTL from §2.
- Reconnects are **unlimited by default** (`KC_NATS_MAX_RECONNECTS=-1`), so the listener recovers
  on its own after long outages.

The only Keycloak-side setting that also benefits NATS is the **JVM DNS TTL (§2)**.
