package com.supportplatform.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Drives the WhatsApp inbound/outbound pollers (whatsapp-domain.md §4, §6).
 * Gated behind {@code app.scheduling.enabled} (default true) so integration
 * tests can turn automatic firing off — a poller racing a test's own
 * assertions on a row it just created would be a flaky test, not a bug in
 * either. Tests that want to exercise a poller call its bean method
 * directly instead, the same way message tests call {@code MessageService}
 * directly rather than waiting on real timing.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
