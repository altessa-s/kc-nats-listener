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
 * No-op fallback used while no NATS connection is available. The event listener SPI does not
 * allow {@link org.keycloak.events.EventListenerProviderFactory#create} to fail, so a no-op
 * instance is returned instead and events are silently dropped.
 */
public class NoopEventListenerProvider implements EventListenerProvider {
    @Override
    public void onEvent(final Event event) {}

    @Override
    public void onEvent(final AdminEvent event, final boolean includeRepresentation) {}

    @Override
    public void close() {}
}
