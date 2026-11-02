package com.supportplatform.client;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a client is told about a failed message. {@code failure_reason} holds
 * Meta's raw text, including trace ids and internal detail, so it is reduced
 * to a code here and never returned as stored.
 *
 * <p>The category says who can act on it. A billing block on the sending
 * account must not read as a fault in the client's own messages.
 */
public record FailureExplanation(String code, Category category, String message) {

    public enum Category {
        /** The sending account or platform: not something the client caused or can fix. */
        PLATFORM,
        /** The recipient's number. */
        RECIPIENT,
        /** The template, or the values sent with it. */
        TEMPLATE,
        OTHER
    }

    /** Covers the forms stored so far: "Meta error 131026:", "code=132000" and "(#132018)". */
    private static final Pattern CODE = Pattern.compile("(?:Meta error |code=|\\(#)(\\d{4,6})");

    private static final Map<String, FailureExplanation> KNOWN = Map.ofEntries(
            known("131042", Category.PLATFORM, "Not delivered because of a billing problem on the sending WhatsApp "
                    + "account. This was not caused by your message."),
            known("131031", Category.PLATFORM, "Not delivered because the sending WhatsApp account is restricted. "
                    + "This was not caused by your message."),
            known("131000", Category.PLATFORM, "WhatsApp had an internal error while sending this message."),
            known("131026", Category.RECIPIENT, "This number could not be reached on WhatsApp. It may be incorrect "
                    + "or not registered on WhatsApp."),
            known("131049", Category.RECIPIENT, "WhatsApp held this message back to limit how many messages this "
                    + "recipient receives."),
            known("131008", Category.TEMPLATE, "A value this template requires was missing."),
            known("132000", Category.TEMPLATE, "The number of values sent does not match this template."),
            known("132018", Category.TEMPLATE, "WhatsApp rejected the values sent with this template."),
            known("132015", Category.TEMPLATE, "WhatsApp has paused this template."));

    private static final FailureExplanation NO_REASON =
            new FailureExplanation(null, Category.OTHER, "WhatsApp did not give a reason for this failure.");

    private static final FailureExplanation NO_RESPONSE =
            new FailureExplanation(null, Category.PLATFORM, "WhatsApp did not respond while this message was being sent.");

    private static final FailureExplanation UNRECOGNISED =
            new FailureExplanation(null, Category.OTHER, "This message could not be delivered.");

    /** @param storedReason {@code notification_log.failure_reason}; null when the message did not fail */
    public static FailureExplanation from(String storedReason) {
        if (storedReason == null || storedReason.isBlank()) {
            return NO_REASON;
        }
        Matcher matcher = CODE.matcher(storedReason);
        if (matcher.find()) {
            String code = matcher.group(1);
            FailureExplanation known = KNOWN.get(code);
            return known != null ? known
                    : new FailureExplanation(code, Category.OTHER, "WhatsApp could not deliver this message.");
        }
        if (storedReason.startsWith("No response from Meta")) {
            return NO_RESPONSE;
        }
        return storedReason.equals("WhatsApp reported delivery failure") ? NO_REASON : UNRECOGNISED;
    }

    private static Map.Entry<String, FailureExplanation> known(String code, Category category, String message) {
        return Map.entry(code, new FailureExplanation(code, category, message));
    }
}
