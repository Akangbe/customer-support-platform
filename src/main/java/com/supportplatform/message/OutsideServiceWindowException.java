package com.supportplatform.message;

/** FR-WA-009: no free-form outbound send is allowed outside the 24h customer-service window. */
public class OutsideServiceWindowException extends RuntimeException {

    public OutsideServiceWindowException(String message) {
        super(message);
    }
}
