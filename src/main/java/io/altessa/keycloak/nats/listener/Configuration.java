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

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration record for the Keycloak NATS JetStream adapter.
 *
 * <p>This record loads configuration from environment variables and provides settings for:
 * <ul>
 *   <li>NATS server connection URL</li>
 *   <li>Admin events JetStream stream configuration</li>
 *   <li>Client events JetStream stream configuration</li>
 * </ul>
 *
 * <p>Subject patterns are fixed:
 * <ul>
 *   <li>Admin events: {@code keycloak.event.admin.>}</li>
 *   <li>Client events: {@code keycloak.event.client.>}</li>
 * </ul>
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code KC_NATS_URL} - NATS server URL (default: nats://localhost:4222)</li>
 *   <li>{@code KC_NATS_ADMIN_STREAM_NAME} - Admin stream name (required to enable admin stream)</li>
 *   <li>{@code KC_NATS_CLIENT_STREAM_NAME} - Client stream name (required to enable client stream)</li>
 *   <li>See README.md for full list of stream configuration options</li>
 * </ul>
 *
 * @param url                  NATS server connection URL
 * @param urlExplicitlySet     Whether KC_NATS_URL was explicitly set in environment variables
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
    /** Subject pattern for admin events stream */
    private static final String ADMIN_STREAM_SUBJECTS = "KEYCLOAK.EVENTS.ADMIN.>";

    /** Subject pattern for client events stream */
    private static final String CLIENT_STREAM_SUBJECTS = "KEYCLOAK.EVENTS.CLIENT.>";

    /** Topic template for admin events: KEYCLOAK.EVENTS.ADMIN.{REALM}.{RESULT}.{RESOURCETYPE}.{OPERATION} */
    public static final String ADMIN_EVENT_TOPIC_TEMPLATE = "KEYCLOAK.EVENTS.ADMIN.%s.%s.%s.%s";

    /** Topic template for client events: KEYCLOAK.EVENTS.CLIENT.{REALM}.{RESULT}.{CLIENTID}.{TYPE} */
    public static final String CLIENT_EVENT_TOPIC_TEMPLATE = "KEYCLOAK.EVENTS.CLIENT.%s.%s.%s.%s";

    /**
     * Loads configuration from system environment variables.
     *
     * <p>Reads {@code KC_NATS_URL} for the NATS connection URL.
     * <p>Reads {@code KC_NATS_CREATE_STREAMS} to determine if streams should be created/updated (default: false).
     * Stream configurations are loaded only if their respective {@code _NAME}
     * environment variables are set.
     *
     * @return A new Configuration instance with loaded settings
     */
    public static Configuration loadFromEnv() {
        final String natsUrlEnv = System.getenv("KC_NATS_URL");
        final boolean urlExplicitlySet = natsUrlEnv != null && !natsUrlEnv.trim().isEmpty();
        final String url = urlExplicitlySet ? natsUrlEnv : Options.DEFAULT_URL;
        final boolean createStreams = "true".equalsIgnoreCase(System.getenv("KC_NATS_CREATE_STREAMS"));

        // Reconnection settings. Default to unlimited reconnects (-1): in Kubernetes a NATS
        // outage (node drain / pod reschedule) can easily exceed the finite NATS default of 60
        // attempts, after which the client gives up and the listener stays in NOOP mode until
        // Keycloak restarts. Unlimited retries let it recover on its own and re-resolve DNS.
        final int maxReconnects = parseIntOrDefault(System.getenv("KC_NATS_MAX_RECONNECTS"), -1);
        final long reconnectWaitSeconds = parseLongOrDefault(System.getenv("KC_NATS_RECONNECT_WAIT_SECONDS"), 2L); // NATS default: 2 seconds

        // Keepalive ping interval. Lower than the NATS default of 120s so a dead TCP connection
        // (e.g. NATS pod killed / node restart) is detected within ~30s and triggers a reconnect.
        final long pingIntervalSeconds = parseLongOrDefault(System.getenv("KC_NATS_PING_INTERVAL_SECONDS"), 30L);

        // Defaults to true: re-resolve the hostname on each (re)connect (Kubernetes-friendly).
        // Set KC_NATS_NO_RESOLVE_HOSTNAMES=false to restore the default jnats IP-pinning behaviour.
        final boolean noResolveHostnames = parseBooleanOrDefault(System.getenv("KC_NATS_NO_RESOLVE_HOSTNAMES"), true);

        final StreamConfiguration adminStreamConfig = buildStreamConfigFromEnv("KC_NATS_ADMIN_STREAM", ADMIN_STREAM_SUBJECTS);
        final StreamConfiguration clientStreamConfig = buildStreamConfigFromEnv("KC_NATS_CLIENT_STREAM", CLIENT_STREAM_SUBJECTS);

        return new Configuration(url, urlExplicitlySet, createStreams, maxReconnects, reconnectWaitSeconds, pingIntervalSeconds, noResolveHostnames, adminStreamConfig, clientStreamConfig);
    }

    /**
     * Builds a JetStream StreamConfiguration from environment variables.
     *
     * @param prefix Environment variable prefix (e.g., "ADMIN_STREAM" or "CLIENT_STREAM")
     * @param subjects Subject pattern for the stream (e.g., "keycloak.event.admin.>")
     * @return StreamConfiguration if NAME is set, null otherwise
     */
    private static StreamConfiguration buildStreamConfigFromEnv(String prefix, String subjects) {
        String name = System.getenv(prefix + "_NAME");
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        StreamConfiguration.Builder builder = StreamConfiguration.builder()
                .name(name)
                .subjects(subjects);

        // Add optional parameters if configured
        String maxBytesStr = System.getenv(prefix + "_MAX_BYTES");
        if (maxBytesStr != null) {
            Long maxBytes = parseLong(maxBytesStr);
            if (maxBytes != null) {
                builder.maxBytes(maxBytes);
            }
        }

        String maxAgeStr = System.getenv(prefix + "_MAX_STREAM_AGE_SECONDS");
        if (maxAgeStr != null) {
            Long maxAge = parseLong(maxAgeStr);
            if (maxAge != null) {
                builder.maxAge(Duration.ofSeconds(maxAge));
            }
        }

        String maxMsgsStr = System.getenv(prefix + "_STREAM_MAX_MSGS");
        if (maxMsgsStr != null) {
            Long maxMsgs = parseLong(maxMsgsStr);
            if (maxMsgs != null) {
                builder.maxMessages(maxMsgs);
            }
        }

        String maxMsgsPerSubjectStr = System.getenv(prefix + "_MAX_MSGS_PER_SUBJECT");
        if (maxMsgsPerSubjectStr != null) {
            Long maxMsgsPerSubject = parseLong(maxMsgsPerSubjectStr);
            if (maxMsgsPerSubject != null) {
                builder.maxMessagesPerSubject(maxMsgsPerSubject);
            }
        }

        String storageTypeStr = System.getenv(prefix + "_STORAGE_TYPE");
        if (storageTypeStr != null) {
            StorageType storageType = parseStorageType(storageTypeStr);
            if (storageType != null) {
                builder.storageType(storageType);
            }
        }

        String retentionPolicyStr = System.getenv(prefix + "_RETENTION_POLICY");
        if (retentionPolicyStr != null) {
            RetentionPolicy retentionPolicy = parseRetentionPolicy(retentionPolicyStr);
            if (retentionPolicy != null) {
                builder.retentionPolicy(retentionPolicy);
            }
        }

        String discardPolicyStr = System.getenv(prefix + "_DISCARD_POLICY");
        if (discardPolicyStr != null) {
            DiscardPolicy discardPolicy = parseDiscardPolicy(discardPolicyStr);
            if (discardPolicy != null) {
                builder.discardPolicy(discardPolicy);
            }
        }

        String duplicateWindowStr = System.getenv(prefix + "_DUPLICATE_WINDOW_SECONDS");
        if (duplicateWindowStr != null) {
            Long duplicateWindow = parseLong(duplicateWindowStr);
            if (duplicateWindow != null) {
                builder.duplicateWindow(Duration.ofSeconds(duplicateWindow));
            }
        }

        String numReplicasStr = System.getenv(prefix + "_NUM_REPLICAS");
        if (numReplicasStr != null) {
            Integer numReplicas = parseInteger(numReplicasStr);
            if (numReplicas != null) {
                builder.replicas(numReplicas);
            }
        }

        return builder.build();
    }

    /**
     * Safely parses a string to Long, returning null if parsing fails.
     */
    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely parses a string to Integer, returning null if parsing fails.
     */
    private static Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely parses a string to int, returning default value if parsing fails.
     */
    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parses a boolean from a string, returning the default if unset/blank.
     * Accepts "true"/"false" case-insensitively; any other non-blank value is treated as false.
     */
    private static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Safely parses a string to long, returning default value if parsing fails.
     */
    private static long parseLongOrDefault(String value, long defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parses storage type from string (case-insensitive).
     *
     * @param value String value (FILE or MEMORY)
     * @return StorageType or null if invalid
     */
    private static StorageType parseStorageType(String value) {
        try {
            return StorageType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses retention policy from string (case-insensitive).
     *
     * @param value String value (LIMITS, INTEREST, or WORKQUEUE)
     * @return RetentionPolicy or null if invalid
     */
    private static RetentionPolicy parseRetentionPolicy(String value) {
        try {
            return RetentionPolicy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses discard policy from string (case-insensitive).
     *
     * @param value String value (OLD or NEW)
     * @return DiscardPolicy or null if invalid
     */
    private static DiscardPolicy parseDiscardPolicy(String value) {
        try {
            return DiscardPolicy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
