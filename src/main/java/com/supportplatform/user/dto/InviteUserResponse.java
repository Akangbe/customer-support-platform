package com.supportplatform.user.dto;

import com.supportplatform.user.User;
import com.supportplatform.user.UserRole;

import java.util.UUID;

/**
 * Distinct from {@link UserSummaryResponse}: this is the one place the
 * invite token is exposed, returned only to the caller who just created the
 * invite. Email delivery of the token is deferred infrastructure work (see
 * identity-and-access.md §1) — until it exists, the inviter relays this
 * link manually.
 */
public record InviteUserResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        String inviteToken
) {
    public static InviteUserResponse from(User user) {
        return new InviteUserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(), user.getInviteToken());
    }
}
