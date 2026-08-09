package com.supportplatform.customer;

/** Thrown only by {@link CustomerService#createManually}, never by the idempotent find-or-create path. */
public class CustomerAlreadyExistsException extends RuntimeException {

    public CustomerAlreadyExistsException(String phone) {
        super("A customer with phone " + phone + " already exists");
    }
}
