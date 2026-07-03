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
import io.nats.client.JetStreamApiException;
import io.nats.client.api.PublishAck;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes incoming events to a JetStream connection
 */
public class NATSEventListenerProvider implements EventListenerProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NATSEventListenerProvider.class);

    private final ObjectMapper objectMapper;
    private final JetStream jetStream;
    private final KeycloakSession session;

    NATSEventListenerProvider(final JetStream jetStream, final KeycloakSession session) {
        this.objectMapper = new ObjectMapper();
        this.jetStream = jetStream;
        this.session = session;
    }

    @Override
    public void onEvent(final Event event) {
        try {
            final String serialized = this.serializeEventWithUserData(event);
            final String key = this.buildKey(event);
            this.send(key, serialized);
        } catch (Exception e) {
            LOGGER.error("Error processing event", e);
            // Fallback to sending original event without enrichment
            final String serialized = this.serialize(event);
            final String key = this.buildKey(event);
            this.send(key, serialized);
        }
    }

    @Override
    public void onEvent(final AdminEvent event, final boolean includeRepresentation) {
        final String serialized = this.serialize(event);
        final String key = this.buildKey(event);
        this.send(key, serialized);
    }

    @Override
    public void close() {
        // We re-use this object so we don't care about this method
        // To close the connection we use NATSEventListenerProvider#closeConnection instead
    }

    private void send(final String key, final String value) {
        try {
            PublishAck ack = this.jetStream.publish(key, value.getBytes(StandardCharsets.UTF_8));
            LOGGER.debug("published new event {} to JetStream with sequence: {}", key, ack.getSeqno());
        } catch (final IOException | JetStreamApiException exception) {
            LOGGER.error("could not send message to JetStream", exception);
        }
    }

    private String serialize(final Object object) {
        try {
            return this.objectMapper.writeValueAsString(object);
        } catch (final JsonProcessingException exception) {
            LOGGER.error("could not serialize event", exception);
            return "{}";
        }
    }

    private String buildKey(final Event event) {
        // KEYCLOAK.EVENTS.CLIENT.<REALM>.<RESULT>.<CLIENTID>.<TYPE>
        final String realmId = event.getRealmId() != null ? event.getRealmId().replace(".", "") : "UNKNOWN";
        final String clientId = event.getClientId() != null ? event.getClientId().replace(".", "") : "UNKNOWN";
        final String eventType = event.getType() != null ? event.getType().toString() : "UNKNOWN";

        return this.normalizeKey(String.format(
                Configuration.CLIENT_EVENT_TOPIC_TEMPLATE,
                realmId,
                event.getError() != null ? "ERROR" : "SUCCESS",
                clientId,
                eventType
        ).toUpperCase());
    }

    private String buildKey(final AdminEvent event) {
        // KEYCLOAK.EVENTS.ADMIN.<REALM>.<RESULT>.<RESOURCETYPE>.<OPERATION>
        final String realmId = event.getRealmId() != null ? event.getRealmId().replace(".", "") : "UNKNOWN";
        final String resourceType = event.getResourceTypeAsString() != null ? event.getResourceTypeAsString() : "UNKNOWN";
        final String operationType = event.getOperationType() != null ? event.getOperationType().toString() : "UNKNOWN";

        return this.normalizeKey(String.format(
                Configuration.ADMIN_EVENT_TOPIC_TEMPLATE,
                realmId,
                event.getError() != null ? "ERROR" : "SUCCESS",
                resourceType,
                operationType
        ).toUpperCase());
    }

    private String normalizeKey(final String key) {
        // Remove everything except 'a-z', 'A-Z', '0-9', ' ', '_', '.' and '-' and replace spaces with underscores
        return key.replaceAll("[^a-zA-Z0-9 _.-]", "").
                replace(" ", "_");
    }

    /**
     * Serializes event with user representation if userId is present.
     *
     * @param event The event to serialize
     * @return JSON string with userRepresentation field added if user found
     */
    private String serializeEventWithUserData(Event event) {
        try {
            // Serialize the event to JSON
            String eventJson = this.objectMapper.writeValueAsString(event);

            // If event has userId, add userRepresentation field
            if (event.getUserId() != null) {
                String userRepresentation = this.buildUserRepresentation(event.getUserId(), event.getRealmId());
                if (userRepresentation != null) {
                    // Parse event JSON and add userRepresentation field
                    ObjectNode eventNode = (ObjectNode) this.objectMapper.readTree(eventJson);
                    eventNode.put("userRepresentation", userRepresentation);
                    return this.objectMapper.writeValueAsString(eventNode);
                }
            }

            return eventJson;
        } catch (JsonProcessingException e) {
            LOGGER.error("Error serializing event with user data", e);
            return this.serialize(event);
        }
    }

    /**
     * Builds user representation JSON string from Keycloak session.
     *
     * @param userId  User ID
     * @param realmId Realm ID
     * @return JSON string representing the user, or null if user not found
     */
    private String buildUserRepresentation(String userId, String realmId) {
        try {
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm == null) {
                LOGGER.warn("Realm not found: {}", realmId);
                return null;
            }

            UserModel user = session.users().getUserById(realm, userId);
            if (user == null) {
                LOGGER.warn("User not found: {} in realm {}", userId, realmId);
                return null;
            }

            // Flatten multi-valued attributes to single string
            Map<String, String> flatAttributes = new HashMap<>();
            Map<String, List<String>> userAttributes = user.getAttributes();
            if (userAttributes != null) {
                for (Map.Entry<String, List<String>> entry : userAttributes.entrySet()) {
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        flatAttributes.put(entry.getKey(), String.join(", ", values));
                    }
                }
            }

            // Build user representation map
            Map<String, Object> userRepresentation = new HashMap<>();
            userRepresentation.put("id", user.getId());
            userRepresentation.put("username", user.getUsername());
            userRepresentation.put("email", user.getEmail());
            userRepresentation.put("firstName", user.getFirstName());
            userRepresentation.put("lastName", user.getLastName());
            userRepresentation.put("emailVerified", user.isEmailVerified());
            userRepresentation.put("enabled", user.isEnabled());
            userRepresentation.put("createdTimestamp", user.getCreatedTimestamp());
            userRepresentation.put("attributes", flatAttributes);

            return this.objectMapper.writeValueAsString(userRepresentation);
        } catch (Exception e) {
            LOGGER.error("Error building user representation for userId: {}, realmId: {}", userId, realmId, e);
            return null;
        }
    }
}
