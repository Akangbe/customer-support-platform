package com.supportplatform.user;

public class InvalidInviteTokenException extends RuntimeException {

    public InvalidInviteTokenException() {
        super("Invalid or expired invitation");
    }
}
