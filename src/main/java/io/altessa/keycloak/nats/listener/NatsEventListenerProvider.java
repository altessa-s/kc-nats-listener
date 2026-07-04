/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nats.client.JetStream;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Publishes Keycloak events to NATS JetStream.
 *
 * <p>Publishing is enlisted after the Keycloak transaction commits, so events belonging to
 * rolled-back transactions are never published. Delivery is at-most-once: if a publish fails,
 * the error is logged and the event is dropped.
 *
 * <p>Client events are enriched with a user representation (username, e-mail, names, attributes),
 * i.e. personal data is published to NATS — downstream consumers and stream retention must be
 * configured accordingly.
 */
public class NatsEventListenerProvider implements EventListenerProvider {

    private static final Logger LOGGER = Logger.getLogger(NatsEventListenerProvider.class);

    /** Shared, thread-safe mapper: creating an ObjectMapper per provider instance is expensive. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Matches every character that is not allowed in a NATS subject token. */
    private static final Pattern INVALID_SUBJECT_CHARS = Pattern.compile("[^a-zA-Z0-9 _.-]");

    private final JetStream jetStream;
    private final KeycloakSession session;

    NatsEventListenerProvider(final JetStream jetStream, final KeycloakSession session) {
        this.jetStream = jetStream;
        this.session = session;
    }

    @Override
    public void onEvent(final Event event) {
        // Enrichment reads from the user store, so it runs now, while the session is active;
        // only the publish itself is deferred until after the transaction commits.
        publishAfterCommit(buildSubject(event), serializeEventWithUserData(event));
    }

    @Override
    public void onEvent(final AdminEvent event, final boolean includeRepresentation) {
        publishAfterCommit(buildSubject(event), serialize(event));
    }

    @Override
    public void close() {
        // Nothing to close: the NATS connection is owned by the factory.
    }

    /**
     * Defers the publish until the current Keycloak transaction completes, so events from
     * rolled-back transactions are not published.
     */
    private void publishAfterCommit(final String subject, final String payload) {
        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                publish(subject, payload);
            }

            @Override
            protected void rollbackImpl() {
                LOGGER.debugf("Transaction rolled back, skipping publish of event %s", subject);
            }
        });
    }

    /**
     * Publishes the payload asynchronously so the Keycloak request thread does not wait for the
     * JetStream acknowledgement. Failures are logged and the event is dropped (at-most-once).
     */
    private void publish(final String subject, final String payload) {
        try {
            this.jetStream.publishAsync(subject, payload.getBytes(StandardCharsets.UTF_8))
                    .whenComplete((ack, error) -> {
                        if (error != null) {
                            LOGGER.errorf(error, "Could not publish event %s to JetStream, the event is dropped", subject);
                        } else {
                            LOGGER.debugf("Published event %s to JetStream with sequence %d", subject, ack.getSeqno());
                        }
                    });
        } catch (final RuntimeException exception) {
            LOGGER.errorf(exception, "Could not publish event %s to JetStream, the event is dropped", subject);
        }
    }

    private static String serialize(final Object object) {
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (final JsonProcessingException exception) {
            LOGGER.error("Could not serialize event", exception);
            return "{}";
        }
    }

    static String buildSubject(final Event event) {
        // KEYCLOAK.EVENTS.CLIENT.<REALM>.<RESULT>.<CLIENTID>.<TYPE>
        final String realmId = event.getRealmId() != null ? event.getRealmId().replace(".", "") : "UNKNOWN";
        final String clientId = event.getClientId() != null ? event.getClientId().replace(".", "") : "UNKNOWN";
        final String eventType = event.getType() != null ? event.getType().toString() : "UNKNOWN";

        return normalizeSubject(String.format(
                Configuration.CLIENT_EVENT_TOPIC_TEMPLATE,
                realmId,
                event.getError() != null ? "ERROR" : "SUCCESS",
                clientId,
                eventType
        ).toUpperCase());
    }

    static String buildSubject(final AdminEvent event) {
        // KEYCLOAK.EVENTS.ADMIN.<REALM>.<RESULT>.<RESOURCETYPE>.<OPERATION>
        final String realmId = event.getRealmId() != null ? event.getRealmId().replace(".", "") : "UNKNOWN";
        final String resourceType = event.getResourceTypeAsString() != null ? event.getResourceTypeAsString() : "UNKNOWN";
        final String operationType = event.getOperationType() != null ? event.getOperationType().toString() : "UNKNOWN";

        return normalizeSubject(String.format(
                Configuration.ADMIN_EVENT_TOPIC_TEMPLATE,
                realmId,
                event.getError() != null ? "ERROR" : "SUCCESS",
                resourceType,
                operationType
        ).toUpperCase());
    }

    /**
     * Removes everything except 'a-z', 'A-Z', '0-9', ' ', '_', '.' and '-', then replaces
     * spaces with underscores.
     */
    static String normalizeSubject(final String subject) {
        return INVALID_SUBJECT_CHARS.matcher(subject).replaceAll("").replace(' ', '_');
    }

    /**
     * Serializes the event, adding a nested {@code userRepresentation} object when the event
     * carries a userId and the user can be resolved. Falls back to the plain event on any error.
     *
     * @param event The event to serialize
     * @return JSON string, with a userRepresentation field added if the user was found
     */
    private String serializeEventWithUserData(final Event event) {
        if (event.getUserId() == null) {
            return serialize(event);
        }
        try {
            final ObjectNode eventNode = OBJECT_MAPPER.valueToTree(event);
            final Map<String, Object> userRepresentation = buildUserRepresentation(event.getUserId(), event.getRealmId());
            if (userRepresentation != null) {
                eventNode.set("userRepresentation", OBJECT_MAPPER.valueToTree(userRepresentation));
            }
            return OBJECT_MAPPER.writeValueAsString(eventNode);
        } catch (final JsonProcessingException | RuntimeException exception) {
            LOGGER.error("Error serializing event with user data, publishing without enrichment", exception);
            return serialize(event);
        }
    }

    /**
     * Builds a user representation from the Keycloak session.
     *
     * @param userId  User ID
     * @param realmId Realm ID
     * @return map describing the user, or null if the realm or user was not found
     */
    private Map<String, Object> buildUserRepresentation(final String userId, final String realmId) {
        try {
            final RealmModel realm = session.realms().getRealm(realmId);
            if (realm == null) {
                LOGGER.warnf("Realm not found: %s", realmId);
                return null;
            }

            final UserModel user = session.users().getUserById(realm, userId);
            if (user == null) {
                LOGGER.warnf("User not found: %s in realm %s", userId, realmId);
                return null;
            }

            // Flatten multi-valued attributes to single string
            final Map<String, String> flatAttributes = new HashMap<>();
            final Map<String, List<String>> userAttributes = user.getAttributes();
            if (userAttributes != null) {
                for (final Map.Entry<String, List<String>> entry : userAttributes.entrySet()) {
                    final List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        flatAttributes.put(entry.getKey(), String.join(", ", values));
                    }
                }
            }

            final Map<String, Object> userRepresentation = new HashMap<>();
            userRepresentation.put("id", user.getId());
            userRepresentation.put("username", user.getUsername());
            userRepresentation.put("email", user.getEmail());
            userRepresentation.put("firstName", user.getFirstName());
            userRepresentation.put("lastName", user.getLastName());
            userRepresentation.put("emailVerified", user.isEmailVerified());
            userRepresentation.put("enabled", user.isEnabled());
            userRepresentation.put("createdTimestamp", user.getCreatedTimestamp());
            userRepresentation.put("attributes", flatAttributes);

            return userRepresentation;
        } catch (final RuntimeException exception) {
            LOGGER.errorf(exception, "Error building user representation for userId: %s, realmId: %s", userId, realmId);
            return null;
        }
    }
}
