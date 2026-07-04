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
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationTest {

    /** Minimal map-backed Config.Scope for tests. */
    private record MapScope(Map<String, String> values) implements Config.Scope {
        @Override
        public String get(final String key) {
            return values.get(key);
        }

        @Override
        public String get(final String key, final String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public String[] getArray(final String key) {
            return null;
        }

        @Override
        public Integer getInt(final String key, final Integer defaultValue) {
            final String value = get(key);
            return value != null ? Integer.valueOf(value) : defaultValue;
        }

        @Override
        public Long getLong(final String key, final Long defaultValue) {
            final String value = get(key);
            return value != null ? Long.valueOf(value) : defaultValue;
        }

        @Override
        public Boolean getBoolean(final String key, final Boolean defaultValue) {
            final String value = get(key);
            return value != null ? Boolean.valueOf(value) : defaultValue;
        }

        @Override
        public Config.Scope scope(final String... scope) {
            return null;
        }

        @Override
        public Config.Scope root() {
            return null;
        }

        @Override
        @Deprecated
        public Set<String> getPropertyNames() {
            return values.keySet();
        }
    }

    @Test
    void defaultsWhenNothingConfigured() {
        final Configuration config = Configuration.load(null, Map.of());

        assertEquals(Options.DEFAULT_URL, config.url());
        assertFalse(config.urlExplicitlySet());
        assertFalse(config.createStreams());
        assertEquals(-1, config.maxReconnects());
        assertEquals(2L, config.reconnectWaitSeconds());
        assertEquals(30L, config.pingIntervalSeconds());
        assertTrue(config.noResolveHostnames());
        assertNull(config.adminStreamConfig());
        assertNull(config.clientStreamConfig());
    }

    @Test
    void readsConnectionSettingsFromEnv() {
        final Configuration config = Configuration.load(null, Map.of(
                "KC_NATS_URL", "nats://nats.example.com:4222",
                "KC_NATS_CREATE_STREAMS", "true",
                "KC_NATS_MAX_RECONNECTS", "5",
                "KC_NATS_RECONNECT_WAIT_SECONDS", "10",
                "KC_NATS_PING_INTERVAL_SECONDS", "15",
                "KC_NATS_NO_RESOLVE_HOSTNAMES", "false"
        ));

        assertEquals("nats://nats.example.com:4222", config.url());
        assertTrue(config.urlExplicitlySet());
        assertTrue(config.createStreams());
        assertEquals(5, config.maxReconnects());
        assertEquals(10L, config.reconnectWaitSeconds());
        assertEquals(15L, config.pingIntervalSeconds());
        assertFalse(config.noResolveHostnames());
    }

    @Test
    void invalidNumericValueFallsBackToDefault() {
        final Configuration config = Configuration.load(null, Map.of(
                "KC_NATS_MAX_RECONNECTS", "not-a-number",
                "KC_NATS_RECONNECT_WAIT_SECONDS", "also-not-a-number"
        ));

        assertEquals(-1, config.maxReconnects());
        assertEquals(2L, config.reconnectWaitSeconds());
    }

    @Test
    void blankValuesAreTreatedAsUnset() {
        final Configuration config = Configuration.load(null, Map.of(
                "KC_NATS_URL", "   ",
                "KC_NATS_ADMIN_STREAM_NAME", ""
        ));

        assertFalse(config.urlExplicitlySet());
        assertEquals(Options.DEFAULT_URL, config.url());
        assertNull(config.adminStreamConfig());
    }

    @Test
    void scopeTakesPrecedenceOverEnv() {
        final Config.Scope scope = new MapScope(Map.of(
                "url", "nats://from-scope:4222",
                "max-reconnects", "7"
        ));
        final Configuration config = Configuration.load(scope, Map.of(
                "KC_NATS_URL", "nats://from-env:4222",
                "KC_NATS_MAX_RECONNECTS", "3"
        ));

        assertEquals("nats://from-scope:4222", config.url());
        assertTrue(config.urlExplicitlySet());
        assertEquals(7, config.maxReconnects());
    }

    @Test
    void urlFromScopeOnlyMarksUrlExplicitlySet() {
        final Config.Scope scope = new MapScope(Map.of("url", "nats://from-scope:4222"));
        final Configuration config = Configuration.load(scope, Map.of());

        assertTrue(config.urlExplicitlySet());
        assertEquals("nats://from-scope:4222", config.url());
    }

    @Test
    void buildsStreamConfigFromEnv() {
        final Configuration config = Configuration.load(null, Map.ofEntries(
                Map.entry("KC_NATS_ADMIN_STREAM_NAME", "KC_ADMIN"),
                Map.entry("KC_NATS_ADMIN_STREAM_MAX_BYTES", "1048576"),
                Map.entry("KC_NATS_ADMIN_STREAM_MAX_STREAM_AGE_SECONDS", "3600"),
                Map.entry("KC_NATS_ADMIN_STREAM_STREAM_MAX_MSGS", "1000"),
                Map.entry("KC_NATS_ADMIN_STREAM_MAX_MSGS_PER_SUBJECT", "100"),
                Map.entry("KC_NATS_ADMIN_STREAM_STORAGE_TYPE", "memory"),
                Map.entry("KC_NATS_ADMIN_STREAM_RETENTION_POLICY", "workqueue"),
                Map.entry("KC_NATS_ADMIN_STREAM_DISCARD_POLICY", "new"),
                Map.entry("KC_NATS_ADMIN_STREAM_DUPLICATE_WINDOW_SECONDS", "120"),
                Map.entry("KC_NATS_ADMIN_STREAM_NUM_REPLICAS", "3")
        ));

        final StreamConfiguration stream = config.adminStreamConfig();
        assertNotNull(stream);
        assertEquals("KC_ADMIN", stream.getName());
        assertEquals(java.util.List.of("KEYCLOAK.EVENTS.ADMIN.>"), stream.getSubjects());
        assertEquals(1048576L, stream.getMaxBytes());
        assertEquals(Duration.ofSeconds(3600), stream.getMaxAge());
        assertEquals(1000L, stream.getMaxMsgs());
        assertEquals(100L, stream.getMaxMsgsPerSubject());
        assertEquals(StorageType.Memory, stream.getStorageType());
        assertEquals(RetentionPolicy.WorkQueue, stream.getRetentionPolicy());
        assertEquals(DiscardPolicy.New, stream.getDiscardPolicy());
        assertEquals(Duration.ofSeconds(120), stream.getDuplicateWindow());
        assertEquals(3, stream.getReplicas());
    }

    @Test
    void clientStreamUsesClientSubjects() {
        final Configuration config = Configuration.load(null, Map.of(
                "KC_NATS_CLIENT_STREAM_NAME", "KC_CLIENT"
        ));

        final StreamConfiguration stream = config.clientStreamConfig();
        assertNotNull(stream);
        assertEquals(java.util.List.of("KEYCLOAK.EVENTS.CLIENT.>"), stream.getSubjects());
        assertNull(config.adminStreamConfig());
    }

    @Test
    void invalidEnumValueIsIgnored() {
        final Configuration config = Configuration.load(null, Map.of(
                "KC_NATS_ADMIN_STREAM_NAME", "KC_ADMIN",
                "KC_NATS_ADMIN_STREAM_STORAGE_TYPE", "bogus",
                "KC_NATS_ADMIN_STREAM_RETENTION_POLICY", "bogus"
        ));

        final StreamConfiguration stream = config.adminStreamConfig();
        assertNotNull(stream);
        // Builder defaults apply when the configured value is invalid
        assertEquals(StorageType.File, stream.getStorageType());
        assertEquals(RetentionPolicy.Limits, stream.getRetentionPolicy());
    }
}
