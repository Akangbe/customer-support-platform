package com.supportplatform.whatsapp;

import java.util.Optional;

/**
 * The Meta delivery errors that no retry will ever clear and that a person
 * has to go and fix — as distinct from the ordinary per-message failures
 * (131026 unreachable number, 131049 per-recipient pacing) which are
 * normal background noise and must not page anyone.
 *
 * <p>The distinction that matters is {@link Scope}: 131042 and 131031 stop
 * <em>every</em> message from the number, agents' conversation replies
 * included, while 132015 stops only the template it names. Both kinds are
 * invisible from the send side — Meta accepts the request, returns a
 * message id, and refuses at delivery — so without this the first signal
 * is a customer complaint. That is exactly how the 2026-09-05 billing
 * outage was found.
 *
 * <p>Adding a code here is one line. Deliberately small: an alert that
 * fires on ordinary delivery failures is one nobody reads.
 */
public enum MetaBlockingError {

    PAYMENT_ISSUE("131042", Scope.ACCOUNT,
            "Billing on the WhatsApp Business Account is unsettled, or no valid payment method is linked to it. "
                    + "Meta is accepting sends and refusing to deliver them. Common causes beyond an unpaid balance: "
                    + "the card is attached to Business Suite but not to the WhatsApp Business Account itself, "
                    + "missing tax information, or an unset currency/timezone."),

    ACCOUNT_RESTRICTED("131031", Scope.ACCOUNT,
            "Meta has restricted or disabled this WhatsApp Business Account, usually for a policy violation or a "
                    + "failed verification. Nothing will deliver until it is resolved in Business Manager."),

    TEMPLATE_PAUSED("132015", Scope.TEMPLATE,
            "Meta paused this template because recipients blocked or reported it during pacing. Sends using it are "
                    + "refused until it is un-paused or replaced. Other templates are unaffected.");

    /** How much is broken — the difference between "one template is off" and "the number is dark". */
    public enum Scope {
        ACCOUNT,
        TEMPLATE
    }

    private final String code;
    private final Scope scope;
    private final String explanation;

    MetaBlockingError(String code, Scope scope, String explanation) {
        this.code = code;
        this.scope = scope;
        this.explanation = explanation;
    }

    public String getCode() {
        return code;
    }

    public Scope getScope() {
        return scope;
    }

    public String getExplanation() {
        return explanation;
    }

    /** @param code Meta's {@code errors[0].code}, as text; {@code null} or unknown yields empty. */
    public static Optional<MetaBlockingError> forCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (MetaBlockingError candidate : values()) {
            if (candidate.code.equals(code.trim())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
