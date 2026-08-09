package com.supportplatform.user;

/** Thrown when an operation would leave a tenant with zero active Owners (ADR-016). */
public class LastOwnerException extends RuntimeException {

    public LastOwnerException() {
        super("This tenant must always have at least one active Owner");
    }
}
