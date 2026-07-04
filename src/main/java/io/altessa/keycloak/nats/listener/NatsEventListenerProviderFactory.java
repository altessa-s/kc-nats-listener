/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.StreamConfiguration;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Provides the {@link NatsEventListenerProvider} or {@link NoopEventListenerProvider} to Keycloak.
 *
 * <p>The NATS connection is owned by this factory and shared by all provider instances. While no
 * connection is available (URL not configured, or the initial connection has not succeeded yet),
 * {@link #create(KeycloakSession)} returns a no-op provider and events are dropped.
 */
public class NatsEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOGGER = Logger.getLogger(NatsEventListenerProviderFactory.class);

    /** Provider id under which the listener is registered in Keycloak. */
    static final String PROVIDER_ID = "kc-nats-listener";

    /** JetStream API error code returned when a stream does not exist. */
    private static final int STREAM_NOT_FOUND_ERROR_CODE = 10059;

    /** Matches the credentials part of a URL, e.g. "user:password@" in "nats://user:password@host:port". */
    private static final Pattern URL_CREDENTIALS_PATTERN = Pattern.compile("://[^@]+@");

    /**
     * The shared JetStream context, or null while no connection is available. Written by the
     * connect/reconnect threads and read by Keycloak request threads in {@link #create(KeycloakSession)},
     * hence volatile. Assigned only after the connection is fully initialized (streams created),
     * so readers never observe a half-initialized state.
     */
    private volatile JetStream jetStream;
    private volatile Connection natsConnection;
    private Configuration config;
    private ScheduledExecutorService reconnectExecutor;
    private ScheduledFuture<?> reconnectTask;

    /**
     * Sanitizes NATS URL by removing credentials for safe logging.
     * Converts "nats://user:password@host:port" to "nats://***:***@host:port"
     *
     * @param url the URL to sanitize
     * @return sanitized URL safe for logging
     */
    static String sanitizeUrlForLogging(final String url) {
        if (url == null) {
            return null;
        }
        return URL_CREDENTIALS_PATTERN.matcher(url).replaceAll("://***:***@");
    }

    @Override
    public EventListenerProvider create(final KeycloakSession session) {
        final JetStream js = this.jetStream; // single volatile read
        if (js == null) {
            return new NoopEventListenerProvider();
        }
        return new NatsEventListenerProvider(js, session);
    }

    @Override
    public void init(final Config.Scope scope) {
        this.config = Configuration.load(scope);
        if (!config.urlExplicitlySet()) {
            LOGGER.info("NATS URL is not configured, using no-op event listener provider (events will not be published to NATS)");
        }
    }

    @Override
    public void postInit(final KeycloakSessionFactory factory) {
        // The initial connection is established here rather than in init(): init() is expected
        // to stay lightweight and must not perform network I/O.
        if (config == null || !config.urlExplicitlySet()) {
            return;
        }

        LOGGER.infof("Initializing NATS event listener adapter, connecting to: %s", sanitizeUrlForLogging(config.url()));
        LOGGER.infof("Reconnection settings: maxReconnects=%d, reconnectWait=%ds",
                config.maxReconnects(), config.reconnectWaitSeconds());

        attemptConnection();
    }

    /**
     * Attempts to establish a connection to the NATS server. On success the JetStream context is
     * published to {@link #jetStream}; on failure a background retry is scheduled (if allowed by
     * maxReconnects). Synchronized so the initial attempt and background retries never overlap.
     */
    private synchronized void attemptConnection() {
        if (this.jetStream != null) {
            return; // already connected
        }

        Connection connection = null;
        try {
            final Options.Builder optionsBuilder = new Options.Builder()
                    .server(config.url())
                    // Lower than the NATS default of 2 minutes so a dead TCP connection (e.g. NATS
                    // pod killed / node restart) is detected within the configured interval and
                    // triggers a reconnect, rather than silently lingering until the next ping cycle.
                    .pingInterval(Duration.ofSeconds(config.pingIntervalSeconds()))
                    .connectionListener(new NatsConnectionListener())
                    .maxReconnects(config.maxReconnects())
                    .reconnectWait(Duration.ofSeconds(config.reconnectWaitSeconds()));

            // Defer hostname resolution to each (re)connect instead of caching resolved IPs in the
            // server pool. Required in Kubernetes: when a NATS pod is rescheduled and gets a new IP,
            // the client must re-resolve the DNS name rather than keep dialing the stale address.
            // Combine with a low JVM networkaddress.cache.ttl. Configurable via KC_NATS_NO_RESOLVE_HOSTNAMES.
            if (config.noResolveHostnames()) {
                optionsBuilder.noResolveHostnames();
            }

            connection = Nats.connect(optionsBuilder.build());
            final JetStream js = connection.jetStream();

            LOGGER.infof("Successfully connected to NATS server at %s", sanitizeUrlForLogging(config.url()));

            if (config.createStreams()) {
                LOGGER.info("CREATE_STREAMS is enabled, creating/updating JetStream streams");
                createConfiguredStreams(connection);
            } else {
                LOGGER.info("CREATE_STREAMS is disabled, skipping stream creation");
            }

            // Publish the connection only after the JetStream context and streams are ready,
            // so create() never observes a half-initialized state.
            this.natsConnection = connection;
            this.jetStream = js;

            LOGGER.info("NATS event listener adapter initialized successfully");

            cancelReconnectTask();
        } catch (final IOException | JetStreamApiException exception) {
            LOGGER.errorf(exception, "Failed to connect to NATS server at %s. Events will NOT be published until the connection is established.",
                    sanitizeUrlForLogging(config.url()));
            closeQuietly(connection);
            scheduleReconnect();
        } catch (final InterruptedException exception) {
            LOGGER.error("NATS connection attempt was interrupted. Events will NOT be published until the connection is established.", exception);
            Thread.currentThread().interrupt(); // Restore interrupt status
            closeQuietly(connection);
            scheduleReconnect();
        }
    }

    /**
     * Creates or updates the configured JetStream streams.
     */
    private void createConfiguredStreams(final Connection connection) throws IOException, JetStreamApiException {
        if (config.adminStreamConfig() != null) {
            buildStream(connection, config.adminStreamConfig());
        }
        if (config.clientStreamConfig() != null) {
            buildStream(connection, config.clientStreamConfig());
        }
    }

    /**
     * Closes a partially initialized connection, keeping the interrupt status intact.
     */
    private static void closeQuietly(final Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (final InterruptedException e) {
            LOGGER.warnf("Interrupted while closing NATS connection during cleanup: %s", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Schedules background reconnection attempts if maxReconnects allows it.
     * No-op when a retry task is already running.
     */
    private synchronized void scheduleReconnect() {
        // Don't schedule reconnect if maxReconnects is 0 (disabled)
        if (config.maxReconnects() == 0) {
            LOGGER.info("Automatic reconnection is disabled (KC_NATS_MAX_RECONNECTS=0)");
            return;
        }

        // The periodic task keeps running until a connection succeeds; no need to reschedule
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }

        if (reconnectExecutor == null) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "NATS-Reconnect-Thread");
                t.setDaemon(true);
                return t;
            });
        }

        final long delaySeconds = config.reconnectWaitSeconds();
        LOGGER.infof("Scheduling background reconnection attempts every %d seconds", delaySeconds);

        reconnectTask = reconnectExecutor.scheduleWithFixedDelay(
                () -> {
                    try {
                        LOGGER.infof("Attempting background reconnection to NATS server at %s", sanitizeUrlForLogging(config.url()));
                        attemptConnection();
                    } catch (final RuntimeException e) {
                        LOGGER.error("Unexpected error during background reconnection attempt", e);
                    }
                },
                delaySeconds,  // initial delay
                delaySeconds,  // period
                TimeUnit.SECONDS
        );
    }

    /**
     * Cancels the background reconnection task if it exists.
     */
    private synchronized void cancelReconnectTask() {
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            LOGGER.info("Cancelling background reconnection task");
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    @Override
    public void close() {
        final ScheduledExecutorService executor;
        final Connection connection;
        synchronized (this) {
            cancelReconnectTask();
            executor = reconnectExecutor;
            reconnectExecutor = null;
            connection = natsConnection;
            natsConnection = null;
            jetStream = null;
        }

        // Shut down outside the monitor so a running retry attempt cannot block the shutdown wait
        if (executor != null) {
            LOGGER.info("Shutting down reconnect executor");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (final InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (connection != null) {
            try {
                LOGGER.info("Closing NATS connection");
                connection.close();
                LOGGER.info("NATS connection closed successfully");
            } catch (final InterruptedException exception) {
                LOGGER.errorf(exception, "Failed to close NATS connection: %s", exception.getMessage());
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
        }
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    private static void buildStream(final Connection connection, final StreamConfiguration streamConfiguration)
            throws IOException, JetStreamApiException {
        try {
            // Try to update existing stream first
            connection.jetStreamManagement().updateStream(streamConfiguration);
            LOGGER.infof("Updated JetStream stream: %s", streamConfiguration.getName());
        } catch (final JetStreamApiException e) {
            // If stream doesn't exist, create it
            if (e.getApiErrorCode() == STREAM_NOT_FOUND_ERROR_CODE) {
                connection.jetStreamManagement().addStream(streamConfiguration);
                LOGGER.infof("Created JetStream stream: %s", streamConfiguration.getName());
            } else {
                throw e;
            }
        }
    }

    /**
     * Logs NATS connection lifecycle events. Reconnection of an established connection is handled
     * by the jnats client itself; the JetStream context stays valid across reconnects.
     */
    private static class NatsConnectionListener implements ConnectionListener {
        @Override
        public void connectionEvent(final Connection connection, final Events event) {
            switch (event) {
                case CONNECTED -> LOGGER.infof("NATS connection established to %s", sanitizeUrlForLogging(connection.getConnectedUrl()));
                case DISCONNECTED -> LOGGER.warn("NATS connection lost. Will attempt to reconnect...");
                case RECONNECTED -> LOGGER.infof("NATS connection re-established to %s", sanitizeUrlForLogging(connection.getConnectedUrl()));
                case CLOSED -> LOGGER.info("NATS connection closed");
                case DISCOVERED_SERVERS -> LOGGER.debug("NATS discovered new servers");
                case RESUBSCRIBED -> LOGGER.info("NATS subscriptions restored after reconnection");
                default -> LOGGER.infof("NATS connection event: %s", event);
            }
        }
    }
}
