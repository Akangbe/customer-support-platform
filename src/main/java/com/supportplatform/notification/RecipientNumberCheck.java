package com.supportplatform.notification;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Rejects a recipient that cannot be a real number in its own country.
 *
 * <p>The request's E.164 pattern checks shape only: a plus and 7 to 15
 * digits. A Nigerian number with two digits too many passes that and fails
 * at Meta with 131026 on every attempt, after the caller has already been
 * told 202 Accepted — so the mistake is found days later, from a failure
 * count, instead of at the moment someone could fix it. libphonenumber
 * knows each country's lengths and prefixes and can say so up front.
 *
 * <p>It cannot catch a real number that simply has no WhatsApp. That is
 * also 131026, and only Meta can know it.
 *
 * <h2>Observe mode</h2>
 * <p>Ships as {@code observe}, like {@link RecipientCeiling}: every number
 * it would reject is logged and nothing is blocked. The risk runs the other
 * way from the one it fixes: libphonenumber's metadata is a snapshot of the
 * world's numbering plans, and a country opening a new mobile range makes
 * real numbers "invalid" until the library is updated. Enforcing before
 * the log has shown what it catches could turn away a real customer.
 * Grep for {@code RECIPIENT NUMBER INVALID (observe}, then flip.
 */
@Component
public class RecipientNumberCheck {

    private static final Logger log = LoggerFactory.getLogger(RecipientNumberCheck.class);

    private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

    private final boolean enforce;

    public RecipientNumberCheck(@Value("${app.notifications.recipient-validation.mode:observe}") String mode) {
        this.enforce = "enforce".equalsIgnoreCase(mode.trim());
    }

    /**
     * @throws InvalidRecipientNumberException when the number is invalid and the mode is {@code enforce}
     */
    public void check(UUID tenantId, String recipient, String templateName) {
        Optional<String> problem = problemWith(recipient);
        if (problem.isEmpty()) {
            return;
        }

        String masked = mask(recipient);
        if (!enforce) {
            log.warn("RECIPIENT NUMBER INVALID (observe, not blocked): recipient={} template='{}' tenant={}: {}. "
                            + "This send would be rejected in enforce mode.",
                    masked, templateName, tenantId, problem.get());
            return;
        }

        log.warn("RECIPIENT NUMBER INVALID (enforced, rejected): recipient={} template='{}' tenant={}: {}",
                masked, templateName, tenantId, problem.get());
        throw new InvalidRecipientNumberException(
                "recipient is not a valid phone number: " + problem.get()
                        + ". Check it with the customer; WhatsApp cannot deliver to it.");
    }

    /** Empty when the number is valid; otherwise what is wrong with it, in words a caller can act on. */
    static Optional<String> problemWith(String recipient) {
        PhoneNumber number;
        try {
            number = PHONE_NUMBERS.parse(recipient, null);
        } catch (NumberParseException e) {
            return Optional.of(e.getErrorType() == NumberParseException.ErrorType.INVALID_COUNTRY_CODE
                    ? "its country code is not in use"
                    : "it could not be read as an international number");
        }
        if (PHONE_NUMBERS.isValidNumber(number)) {
            return Optional.empty();
        }

        String region = PHONE_NUMBERS.getRegionCodeForCountryCode(number.getCountryCode());
        if (PhoneNumberUtil.REGION_CODE_FOR_NON_GEO_ENTITY.equals(region) || "ZZ".equals(region)) {
            return Optional.of("+" + number.getCountryCode() + " is not a country code in use");
        }
        String country = region + " (+" + number.getCountryCode() + ")";
        return Optional.of(switch (PHONE_NUMBERS.isPossibleNumberWithReason(number)) {
            case TOO_LONG -> "it has too many digits for " + country;
            case TOO_SHORT -> "it has too few digits for " + country;
            case INVALID_LENGTH -> "its length is not used for any number in " + country;
            case INVALID_COUNTRY_CODE -> "+" + number.getCountryCode() + " is not a country code in use";
            // Some countries' plans allow long lengths for rare number types,
            // so a mobile number with extra digits can still be "possible" —
            // Nigeria allows up to 14. Name both likely causes rather than
            // guess which one this is.
            default -> "it does not match any number in " + country
                    + "; check for extra or missing digits";
        });
    }

    private static String mask(String recipient) {
        if (recipient == null || recipient.length() < 4) {
            return "****";
        }
        return "****" + recipient.substring(recipient.length() - 4);
    }
}
