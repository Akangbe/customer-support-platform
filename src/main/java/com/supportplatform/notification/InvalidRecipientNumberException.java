package com.supportplatform.notification;

/**
 * The recipient cannot be a real number in its country, so WhatsApp could
 * never deliver to it.
 *
 * <p>A 422 at the moment of sending rather than a 202 followed by Meta's
 * 131026 later: the caller learns about the typo while the person who made
 * it is still there to correct it, and no row is written, so the number is
 * not counted as a failed delivery.
 */
public class InvalidRecipientNumberException extends RuntimeException {

    public InvalidRecipientNumberException(String message) {
        super(message);
    }
}
