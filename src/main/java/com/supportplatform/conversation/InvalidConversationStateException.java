package com.supportplatform.conversation;

/** A status/assignment transition that the state machine doesn't allow from the conversation's current state. */
public class InvalidConversationStateException extends RuntimeException {

    public InvalidConversationStateException(String message) {
        super(message);
    }
}
