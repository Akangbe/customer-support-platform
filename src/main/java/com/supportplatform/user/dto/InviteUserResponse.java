package com.supportplatform.user.dto;

import com.supportplatform.user.User;
import com.supportplatform.user.UserRole;

import java.util.UUID;

/**
 * Distinct from {@link UserSummaryResponse}: this is the one place the
 * invite token is exposed, returned only to the caller who just created the
 * invite. The invitee is also emailed an accept-invite link via SES
 * (identity-and-access.md §8), but that delivery is best-effort — this
 * token stays in the response so the inviter can relay it manually if the
 * email never arrives or SES isn't configured.
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
