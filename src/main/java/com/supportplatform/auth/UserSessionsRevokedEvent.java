package com.supportplatform.auth;

import java.util.UUID;

/** A user's existing sessions were ended, so anything still open on their behalf must close too. */
public record UserSessionsRevokedEvent(UUID userId) {
}
