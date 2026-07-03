/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import io.nats.client.*;
import io.nats.client.api.StreamConfiguration;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class NatsConnectionListener implements ConnectionListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(NatsConnectionListener.class);
    private final NATSEventListenerProviderFactory factory;

    NatsConnectionListener(NATSEventListenerProviderFactory factory) {
        this.factory = factory;
    }

    public void connectionEvent(Connection natsConnection, Events event) {
        switch (event) {
            case CONNECTED:
                LOGGER.info("NATS connection established to {}",
                        NATSEventListenerProviderFactory.sanitizeUrlForLogging(natsConnection.getConnectedUrl()));
                factory.updateConnectionState(NATSEventListenerProviderFactory.ConnectionState.CONNECTED);
                break;
            case DISCONNECTED:
                LOGGER.warn("NATS connection lost. Will attempt to reconnect...");
                // Don't change state here - wait for either RECONNECTED or give up
                break;
            case RECONNECTED:
                LOGGER.info("NATS connection re-established to {}",
                        NATSEventListenerProviderFactory.sanitizeUrlForLogging(natsConnection.getConnectedUrl()));
                factory.updateConnectionState(NATSEventListenerProviderFactory.ConnectionState.CONNECTED);
                break;
            case CLOSED:
                LOGGER.info("NATS connection closed");
                break;
            case DISCOVERED_SERVERS:
                LOGGER.debug("NATS discovered new servers");
                break;
            case RESUBSCRIBED:
                LOGGER.info("NATS subscriptions restored after reconnection");
                break;
            default:
                LOGGER.info("NATS connection event: {}", event);
                break;
        }
    }
}

/**
 * Provides the {@link NATSEventListenerProvider} or {@link NOOPEventListenerProvider} to Keycloak
 */
public class NATSEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(NATSEventListenerProviderFactory.class);

    enum ConnectionState {
        NOT_INITIALIZED,
        CONNECTED,
        FAILED
    }

    /**
     * Sanitizes NATS URL by removing credentials for safe logging.
     * Converts "nats://user:password@host:port" to "nats://***:***@host:port"
     *
     * @param url the URL to sanitize
     * @return sanitized URL safe for logging
     */
    static String sanitizeUrlForLogging(String url) {
        if (url == null) {
            return null;
        }
        // Replace credentials in format "user:password@" with "***:***@"
        return url.replaceAll("://[^@]+@", "://***:***@");
    }

    private volatile ConnectionState connectionState = ConnectionState.NOT_INITIALIZED;
    private JetStream jetStream;
    private Connection natsConnection;
    private Configuration config;
    private ScheduledExecutorService reconnectExecutor;
    private ScheduledFuture<?> reconnectTask;

    @Override
    public EventListenerProvider create(final KeycloakSession session) {
        // Create a new provider instance for each session, passing the session
        if (connectionState != ConnectionState.CONNECTED) {
            if (connectionState == ConnectionState.FAILED) {
                LOGGER.debug("NATS connection failed during initialization, using NOOP provider");
            }
            return new NOOPEventListenerProvider();
        }
        return new NATSEventListenerProvider(jetStream, session);
    }

    @Override
    public void init(final Config.Scope unusedConfig) {
        // We use our own configuration as I don't want to mess around with XML from two thousand years ago
        this.config = Configuration.loadFromEnv();

        // Only connect to NATS if KC_NATS_URL is explicitly set
        if (!config.urlExplicitlySet()) {
            LOGGER.info("KC_NATS_URL not set, using NOOP event listener provider (events will not be published to NATS)");
            this.connectionState = ConnectionState.FAILED;
            return;
        }

        LOGGER.info("Initializing NATS event listener adapter, connecting to: {}", sanitizeUrlForLogging(config.url()));
        LOGGER.info("Reconnection settings: maxReconnects={}, reconnectWait={}s",
                config.maxReconnects(), config.reconnectWaitSeconds());

        // Attempt initial connection
        attemptConnection();
    }

    /**
     * Attempts to establish connection to NATS server.
     * If connection fails and maxReconnects allows, schedules background retry.
     */
    private void attemptConnection() {
        try {
            // Establish connection to NATS server with automatic reconnection
            Options.Builder optionsBuilder = new Options.Builder()
                    .server(config.url())
                    // Lower than the NATS default of 2 minutes so a dead TCP connection (e.g. NATS
                    // pod killed / node restart) is detected within the configured interval and
                    // triggers a reconnect, rather than silently lingering until the next ping cycle.
                    .pingInterval(Duration.ofSeconds(config.pingIntervalSeconds()))
                    .connectionListener(new NatsConnectionListener(this))
                    .maxReconnects(config.maxReconnects())
                    .reconnectWait(Duration.ofSeconds(config.reconnectWaitSeconds()));

            // Defer hostname resolution to each (re)connect instead of caching resolved IPs in the
            // server pool. Required in Kubernetes: when a NATS pod is rescheduled and gets a new IP,
            // the client must re-resolve the DNS name rather than keep dialing the stale address.
            // Combine with a low JVM networkaddress.cache.ttl. Configurable via KC_NATS_NO_RESOLVE_HOSTNAMES.
            if (config.noResolveHostnames()) {
                optionsBuilder.noResolveHostnames();
            }

            Options options = optionsBuilder.build();
            this.natsConnection = Nats.connect(options);
            this.jetStream = this.natsConnection.jetStream();

            LOGGER.info("Successfully connected to NATS server at {}", sanitizeUrlForLogging(config.url()));

            // Create streams only if CREATE_STREAMS flag is enabled
            if (config.createStreams()) {
                LOGGER.info("CREATE_STREAMS is enabled, creating/updating JetStream streams");

                if (config.adminStreamConfig() != null) {
                    buildStream(config.adminStreamConfig());
                }

                if (config.clientStreamConfig() != null) {
                    buildStream(config.clientStreamConfig());
                }
            } else {
                LOGGER.info("CREATE_STREAMS is disabled, skipping stream creation");
            }

            // Connection state is set by ConnectionListener when CONNECTED event fires
            LOGGER.info("NATS event listener adapter initialized successfully");

            // Cancel any pending reconnect task if we successfully connected
            cancelReconnectTask();

        } catch (final IOException exception) {
            LOGGER.error("Failed to connect to NATS server at {}: {}", sanitizeUrlForLogging(config.url()), exception.getMessage());
            LOGGER.error("Events will NOT be published to NATS. Provider will use NOOP mode.", exception);
            handleConnectionFailure();
            scheduleReconnect();
        } catch (final InterruptedException exception) {
            LOGGER.error("NATS connection was interrupted: {}", exception.getMessage());
            LOGGER.error("Events will NOT be published to NATS. Provider will use NOOP mode.", exception);
            Thread.currentThread().interrupt(); // Restore interrupt status
            handleConnectionFailure();
            scheduleReconnect();
        } catch (final JetStreamApiException exception) {
            LOGGER.error("Failed to initialize JetStream streams: {} (error code: {})",
                    exception.getMessage(), exception.getApiErrorCode());
            LOGGER.error("Events will NOT be published to NATS. Provider will use NOOP mode.", exception);
            handleConnectionFailure();
            scheduleReconnect();
        }
    }

    /**
     * Handles connection failure by cleaning up resources and marking state as failed.
     */
    private void handleConnectionFailure() {
        this.connectionState = ConnectionState.FAILED;

        // Clean up partial connection if exists
        if (this.natsConnection != null) {
            try {
                this.natsConnection.close();
            } catch (InterruptedException e) {
                LOGGER.warn("Failed to close NATS connection during cleanup: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }

        this.natsConnection = null;
        this.jetStream = null;
    }

    /**
     * Schedules background reconnection attempts if maxReconnects allows it.
     */
    private void scheduleReconnect() {
        // Don't schedule reconnect if maxReconnects is 0 (disabled)
        if (config.maxReconnects() == 0) {
            LOGGER.info("Automatic reconnection is disabled (NATS_MAX_RECONNECTS=0)");
            return;
        }

        // Initialize executor if needed
        if (reconnectExecutor == null) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NATS-Reconnect-Thread");
                t.setDaemon(true);
                return t;
            });
        }

        // Cancel existing task if any
        cancelReconnectTask();

        // Schedule periodic reconnection attempts
        long delaySeconds = config.reconnectWaitSeconds();
        LOGGER.info("Scheduling background reconnection attempts every {} seconds", delaySeconds);

        reconnectTask = reconnectExecutor.scheduleWithFixedDelay(
                () -> {
                    try {
                        LOGGER.info("Attempting background reconnection to NATS server at {}", sanitizeUrlForLogging(config.url()));
                        attemptConnection();
                    } catch (Exception e) {
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
    private void cancelReconnectTask() {
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            LOGGER.info("Cancelling background reconnection task");
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    /**
     * Updates the connection state. Called by ConnectionListener when connection events occur.
     *
     * @param newState the new connection state
     */
    void updateConnectionState(ConnectionState newState) {
        ConnectionState oldState = this.connectionState;
        this.connectionState = newState;

        if (oldState != newState) {
            LOGGER.info("Connection state changed from {} to {}", oldState, newState);
        }
    }

    @Override
    public void postInit(final KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
        // Cancel background reconnection task
        cancelReconnectTask();

        // Shutdown reconnect executor
        if (reconnectExecutor != null) {
            LOGGER.info("Shutting down reconnect executor");
            reconnectExecutor.shutdown();
            try {
                if (!reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                reconnectExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            reconnectExecutor = null;
        }

        // Close the NATS connection
        if (this.connectionState == ConnectionState.CONNECTED && this.natsConnection != null) {
            try {
                LOGGER.info("Closing NATS connection");
                this.natsConnection.close();
                this.connectionState = ConnectionState.NOT_INITIALIZED;
                LOGGER.info("NATS connection closed successfully");
            } catch (final InterruptedException exception) {
                LOGGER.error("Failed to close NATS connection: {}", exception.getMessage(), exception);
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
        } else if (this.connectionState == ConnectionState.FAILED) {
            LOGGER.debug("Skipping close, NATS connection was never established");
        }
    }

    @Override
    public String getId() {
        return "kc-nats-listener";
    }

    private void buildStream(StreamConfiguration streamConfiguration) throws IOException, JetStreamApiException {
        try {
            // Try to update existing stream first
            this.natsConnection.jetStreamManagement().updateStream(streamConfiguration);
            LOGGER.info("Updated JetStream stream: {}", streamConfiguration.getName());
        } catch (JetStreamApiException e) {
            // If stream doesn't exist, create it
            if (e.getApiErrorCode() == 10059) { // Stream not found
                this.natsConnection.jetStreamManagement().addStream(streamConfiguration);
                LOGGER.info("Created JetStream stream: {}", streamConfiguration.getName());
            } else {
                // Re-throw other errors
                throw e;
            }
        }
    }

}
