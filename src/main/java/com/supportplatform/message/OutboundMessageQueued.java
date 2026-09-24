package com.supportplatform.message;

import java.util.UUID;

/**
 * An outbound message has been stored PENDING and is waiting to reach
 * WhatsApp. Published inside the sending transaction, so a listener bound
 * to AFTER_COMMIT only hears about a row it can actually read.
 */
public record OutboundMessageQueued(UUID tenantId, UUID messageId) {
}
