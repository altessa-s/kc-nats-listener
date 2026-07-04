/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NatsEventListenerProviderFactoryTest {

    @Test
    void sanitizeUrlMasksCredentials() {
        assertEquals("nats://***:***@nats.example.com:4222",
                NatsEventListenerProviderFactory.sanitizeUrlForLogging("nats://user:secret@nats.example.com:4222"));
    }

    @Test
    void sanitizeUrlKeepsUrlWithoutCredentials() {
        assertEquals("nats://nats.example.com:4222",
                NatsEventListenerProviderFactory.sanitizeUrlForLogging("nats://nats.example.com:4222"));
    }

    @Test
    void sanitizeUrlHandlesNull() {
        assertNull(NatsEventListenerProviderFactory.sanitizeUrlForLogging(null));
    }

    @Test
    void providerIdIsStable() {
        assertEquals("kc-nats-listener", new NatsEventListenerProviderFactory().getId());
    }
}
