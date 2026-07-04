/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NatsEventListenerProviderTest {

    @Test
    void normalizeSubjectRemovesInvalidCharactersAndReplacesSpaces() {
        assertEquals("A_B.C-D1", NatsEventListenerProvider.normalizeSubject("A B.C-D*>@1"));
    }

    @Test
    void buildsClientEventSubject() {
        final Event event = new Event();
        event.setRealmId("my.realm");
        event.setClientId("account console");
        event.setType(EventType.LOGIN);

        assertEquals("KEYCLOAK.EVENTS.CLIENT.MYREALM.SUCCESS.ACCOUNT_CONSOLE.LOGIN",
                NatsEventListenerProvider.buildSubject(event));
    }

    @Test
    void buildsClientEventSubjectWithErrorResult() {
        final Event event = new Event();
        event.setRealmId("example");
        event.setClientId("web-app");
        event.setType(EventType.LOGIN_ERROR);
        event.setError("invalid_user_credentials");

        assertEquals("KEYCLOAK.EVENTS.CLIENT.EXAMPLE.ERROR.WEB-APP.LOGIN_ERROR",
                NatsEventListenerProvider.buildSubject(event));
    }

    @Test
    void buildsClientEventSubjectWithUnknownPlaceholders() {
        final Event event = new Event();

        assertEquals("KEYCLOAK.EVENTS.CLIENT.UNKNOWN.SUCCESS.UNKNOWN.UNKNOWN",
                NatsEventListenerProvider.buildSubject(event));
    }

    @Test
    void buildsAdminEventSubject() {
        final AdminEvent event = new AdminEvent();
        event.setRealmId("example");
        event.setResourceTypeAsString("USER");
        event.setOperationType(OperationType.UPDATE);

        assertEquals("KEYCLOAK.EVENTS.ADMIN.EXAMPLE.SUCCESS.USER.UPDATE",
                NatsEventListenerProvider.buildSubject(event));
    }
}
