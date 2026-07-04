/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import io.nats.client.Options;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.jboss.logging.Logger;
import org.keycloak.Config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration record for the Keycloak NATS JetStream adapter.
 *
 * <p>Every option is resolved from two sources, in order of precedence:
 * <ol>
 *   <li>The SPI configuration scope — {@code keycloak.conf} entries or CLI options in the form
 *       {@code --spi-events-listener--kc-nats-listener--<option>} (e.g. {@code --spi-events-listener--kc-nats-listener--url}).
 *       The scope key is the environment variable name without the {@code KC_NATS_} prefix,
 *       lower-cased, with underscores replaced by dashes (e.g. {@code KC_NATS_MAX_RECONNECTS} → {@code max-reconnects}).</li>
 *   <li>Environment variables ({@code KC_NATS_*}).</li>
 * </ol>
 *
 * <p>Subject patterns are fixed:
 * <ul>
 *   <li>Admin events: {@code KEYCLOAK.EVENTS.ADMIN.>}</li>
 *   <li>Client events: {@code KEYCLOAK.EVENTS.CLIENT.>}</li>
 * </ul>
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code KC_NATS_URL} - NATS server URL (default: nats://localhost:4222)</li>
 *   <li>{@code KC_NATS_ADMIN_STREAM_NAME} - Admin stream name (required to enable admin stream)</li>
 *   <li>{@code KC_NATS_CLIENT_STREAM_NAME} - Client stream name (required to enable client stream)</li>
 *   <li>See docs/configuration.md for the full list of stream configuration options</li>
 * </ul>
 *
 * @param url                  NATS server connection URL
 * @param urlExplicitlySet     Whether the NATS URL was explicitly configured (scope or environment)
 * @param createStreams        Whether to create/update JetStream streams on startup
 * @param maxReconnects        Maximum number of reconnection attempts (0 = no reconnect, -1 = unlimited)
 * @param reconnectWaitSeconds Wait time in seconds between reconnection attempts
 * @param pingIntervalSeconds  Interval in seconds between NATS keepalive pings (lower = faster dead-connection detection)
 * @param noResolveHostnames   When true (default), the client re-resolves the hostname on each (re)connect
 *                             instead of caching resolved IPs — required in Kubernetes. Set false to restore
 *                             the default jnats behaviour of resolving the hostname into a pinned IP list.
 * @param adminStreamConfig    Admin events stream configuration, or null if not configured
 * @param clientStreamConfig   Client events stream configuration, or null if not configured
 */
public record Configuration(
        String url,
        boolean urlExplicitlySet,
        boolean createStreams,
        int maxReconnects,
        long reconnectWaitSeconds,
        long pingIntervalSeconds,
        boolean noResolveHostnames,
        StreamConfiguration adminStreamConfig,
        StreamConfiguration clientStreamConfig
) {
    private static final Logger LOGGER = Logger.getLogger(Configuration.class);

    /** Prefix shared by all environment variables of this adapter. */
    private static final String ENV_PREFIX = "KC_NATS_";

    /** Subject pattern for admin events stream */
    private static final String ADMIN_STREAM_SUBJECTS = "KEYCLOAK.EVENTS.ADMIN.>";

    /** Subject pattern for client events stream */
    private static final String CLIENT_STREAM_SUBJECTS = "KEYCLOAK.EVENTS.CLIENT.>";

    /** Topic template for admin events: KEYCLOAK.EVENTS.ADMIN.{REALM}.{RESULT}.{RESOURCETYPE}.{OPERATION} */
    public static final String ADMIN_EVENT_TOPIC_TEMPLATE = "KEYCLOAK.EVENTS.ADMIN.%s.%s.%s.%s";

    /** Topic template for client events: KEYCLOAK.EVENTS.CLIENT.{REALM}.{RESULT}.{CLIENTID}.{TYPE} */
    public static final String CLIENT_EVENT_TOPIC_TEMPLATE = "KEYCLOAK.EVENTS.CLIENT.%s.%s.%s.%s";

    /**
     * Loads configuration from the SPI configuration scope and system environment variables.
     *
     * @param scope the SPI configuration scope passed by Keycloak, may be null
     * @return A new Configuration instance with loaded settings
     */
    public static Configuration load(final Config.Scope scope) {
        return load(scope, System.getenv());
    }

    /**
     * Loads configuration from the given scope and environment map. Extracted for testability.
     *
     * @param scope the SPI configuration scope, may be null
     * @param env   the environment variables to read from
     * @return A new Configuration instance with loaded settings
     */
    static Configuration load(final Config.Scope scope, final Map<String, String> env) {
        final String configuredUrl = getOption(scope, env, "KC_NATS_URL");
        final boolean urlExplicitlySet = configuredUrl != null;
        final String url = urlExplicitlySet ? configuredUrl : Options.DEFAULT_URL;
        final boolean createStreams = getBooleanOption(scope, env, "KC_NATS_CREATE_STREAMS", false);

        // Reconnection settings. Default to unlimited reconnects (-1): in Kubernetes a NATS
        // outage (node drain / pod reschedule) can easily exceed the finite NATS default of 60
        // attempts, after which the client gives up and the listener stays in no-op mode until
        // Keycloak restarts. Unlimited retries let it recover on its own and re-resolve DNS.
        final int maxReconnects = getIntOption(scope, env, "KC_NATS_MAX_RECONNECTS", -1);
        final long reconnectWaitSeconds = getLongOption(scope, env, "KC_NATS_RECONNECT_WAIT_SECONDS", 2L); // NATS default: 2 seconds

        // Keepalive ping interval. Lower than the NATS default of 120s so a dead TCP connection
        // (e.g. NATS pod killed / node restart) is detected within ~30s and triggers a reconnect.
        final long pingIntervalSeconds = getLongOption(scope, env, "KC_NATS_PING_INTERVAL_SECONDS", 30L);

        // Defaults to true: re-resolve the hostname on each (re)connect (Kubernetes-friendly).
        // Set KC_NATS_NO_RESOLVE_HOSTNAMES=false to restore the default jnats IP-pinning behaviour.
        final boolean noResolveHostnames = getBooleanOption(scope, env, "KC_NATS_NO_RESOLVE_HOSTNAMES", true);

        final StreamConfiguration adminStreamConfig = buildStreamConfig(scope, env, "KC_NATS_ADMIN_STREAM", ADMIN_STREAM_SUBJECTS);
        final StreamConfiguration clientStreamConfig = buildStreamConfig(scope, env, "KC_NATS_CLIENT_STREAM", CLIENT_STREAM_SUBJECTS);

        return new Configuration(url, urlExplicitlySet, createStreams, maxReconnects, reconnectWaitSeconds, pingIntervalSeconds, noResolveHostnames, adminStreamConfig, clientStreamConfig);
    }

    /**
     * Builds a JetStream StreamConfiguration from the scope and environment.
     *
     * @param scope    the SPI configuration scope, may be null
     * @param env      the environment variables to read from
     * @param prefix   Environment variable prefix (e.g., "KC_NATS_ADMIN_STREAM" or "KC_NATS_CLIENT_STREAM")
     * @param subjects Subject pattern for the stream (e.g., "KEYCLOAK.EVENTS.ADMIN.>")
     * @return StreamConfiguration if {@code <prefix>_NAME} is set, null otherwise
     */
    private static StreamConfiguration buildStreamConfig(final Config.Scope scope, final Map<String, String> env,
                                                         final String prefix, final String subjects) {
        final String name = getOption(scope, env, prefix + "_NAME");
        if (name == null) {
            return null;
        }

        final StreamConfiguration.Builder builder = StreamConfiguration.builder()
                .name(name)
                .subjects(subjects);

        final Long maxBytes = getLongOption(scope, env, prefix + "_MAX_BYTES");
        if (maxBytes != null) {
            builder.maxBytes(maxBytes);
        }

        final Long maxAgeSeconds = getLongOption(scope, env, prefix + "_MAX_STREAM_AGE_SECONDS");
        if (maxAgeSeconds != null) {
            builder.maxAge(Duration.ofSeconds(maxAgeSeconds));
        }

        final Long maxMessages = getLongOption(scope, env, prefix + "_STREAM_MAX_MSGS");
        if (maxMessages != null) {
            builder.maxMessages(maxMessages);
        }

        final Long maxMessagesPerSubject = getLongOption(scope, env, prefix + "_MAX_MSGS_PER_SUBJECT");
        if (maxMessagesPerSubject != null) {
            builder.maxMessagesPerSubject(maxMessagesPerSubject);
        }

        final StorageType storageType = getEnumOption(scope, env, prefix + "_STORAGE_TYPE", StorageType.class);
        if (storageType != null) {
            builder.storageType(storageType);
        }

        final RetentionPolicy retentionPolicy = getEnumOption(scope, env, prefix + "_RETENTION_POLICY", RetentionPolicy.class);
        if (retentionPolicy != null) {
            builder.retentionPolicy(retentionPolicy);
        }

        final DiscardPolicy discardPolicy = getEnumOption(scope, env, prefix + "_DISCARD_POLICY", DiscardPolicy.class);
        if (discardPolicy != null) {
            builder.discardPolicy(discardPolicy);
        }

        final Long duplicateWindowSeconds = getLongOption(scope, env, prefix + "_DUPLICATE_WINDOW_SECONDS");
        if (duplicateWindowSeconds != null) {
            builder.duplicateWindow(Duration.ofSeconds(duplicateWindowSeconds));
        }

        final Integer numReplicas = getIntegerOption(scope, env, prefix + "_NUM_REPLICAS");
        if (numReplicas != null) {
            builder.replicas(numReplicas);
        }

        return builder.build();
    }

    /**
     * Resolves an option value: SPI scope first, then the environment variable.
     * Blank values are treated as unset.
     *
     * @return the trimmed value, or null if the option is not set
     */
    private static String getOption(final Config.Scope scope, final Map<String, String> env, final String envKey) {
        if (scope != null) {
            final String value = scope.get(scopeKey(envKey));
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        final String value = env.get(envKey);
        return value != null && !value.trim().isEmpty() ? value.trim() : null;
    }

    /**
     * Maps an environment variable name to the corresponding SPI scope key,
     * e.g. {@code KC_NATS_MAX_RECONNECTS} → {@code max-reconnects}.
     */
    private static String scopeKey(final String envKey) {
        return envKey.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean getBooleanOption(final Config.Scope scope, final Map<String, String> env,
                                            final String envKey, final boolean defaultValue) {
        final String value = getOption(scope, env, envKey);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    private static int getIntOption(final Config.Scope scope, final Map<String, String> env,
                                    final String envKey, final int defaultValue) {
        final Integer value = getIntegerOption(scope, env, envKey);
        return value != null ? value : defaultValue;
    }

    private static long getLongOption(final Config.Scope scope, final Map<String, String> env,
                                      final String envKey, final long defaultValue) {
        final Long value = getLongOption(scope, env, envKey);
        return value != null ? value : defaultValue;
    }

    /**
     * @return the option parsed as Long, or null if unset or not a valid number (logged as a warning)
     */
    private static Long getLongOption(final Config.Scope scope, final Map<String, String> env, final String envKey) {
        final String value = getOption(scope, env, envKey);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            LOGGER.warnf("Ignoring invalid numeric value '%s' for option %s", value, envKey);
            return null;
        }
    }

    /**
     * @return the option parsed as Integer, or null if unset or not a valid number (logged as a warning)
     */
    private static Integer getIntegerOption(final Config.Scope scope, final Map<String, String> env, final String envKey) {
        final String value = getOption(scope, env, envKey);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            LOGGER.warnf("Ignoring invalid numeric value '%s' for option %s", value, envKey);
            return null;
        }
    }

    /**
     * @return the option parsed as the given enum type (case-insensitive), or null if unset
     * or not a valid constant (logged as a warning)
     */
    private static <E extends Enum<E>> E getEnumOption(final Config.Scope scope, final Map<String, String> env,
                                                       final String envKey, final Class<E> type) {
        final String value = getOption(scope, env, envKey);
        if (value == null) {
            return null;
        }
        // Case-insensitive matching: jnats enum constants are CamelCase (e.g. Memory, WorkQueue),
        // while the documented option values are upper-case (MEMORY, WORKQUEUE).
        for (final E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        LOGGER.warnf("Ignoring invalid value '%s' for option %s", value, envKey);
        return null;
    }
}
