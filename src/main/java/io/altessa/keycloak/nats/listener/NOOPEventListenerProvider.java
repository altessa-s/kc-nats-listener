/*
 * Copyright 2026 ALTESSA SOLUTIONS INC.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for full license text.
 */

package io.altessa.keycloak.nats.listener;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;

/**
 * Does nothing as I couldn't find a way of cancelling event listener provider creation with an error
 */
public class NOOPEventListenerProvider implements EventListenerProvider {
    @Override
    public void onEvent(final Event event) {}

    @Override
    public void onEvent(final AdminEvent event, final boolean includeRepresentation) {}

    @Override
    public void close() {}
}
