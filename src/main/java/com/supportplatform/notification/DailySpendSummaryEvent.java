package com.supportplatform.notification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A finished UTC day of one API key's sends, ready to be told to the
 * integrator behind the key and the tenant's Owners and Admins.
 *
 * @param sends     every notification the key sent that day, whatever became of it
 * @param delivered those Meta confirmed as delivered or read — the ones Meta charges for
 * @param failed    those that never reached the recipient, and cost nothing
 * @param cost      {@code delivered × pricePerDelivered}: an estimate at the configured
 *                  rate, not Meta's invoice
 */
public record DailySpendSummaryEvent(UUID tenantId,
                                       UUID apiKeyId,
                                       LocalDate periodStart,
                                       long sends,
                                       long delivered,
                                       long failed,
                                       BigDecimal pricePerDelivered,
                                       BigDecimal cost,
                                       String currency) {

    /** Sent, but with no delivery receipt yet: not charged so far, and may still be. */
    public long awaitingConfirmation() {
        return Math.max(0, sends - delivered - failed);
    }
}
