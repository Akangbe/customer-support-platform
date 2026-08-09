package com.supportplatform.user;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.user.dto.ChangeRoleRequest;
import com.supportplatform.user.dto.InviteUserRequest;
import com.supportplatform.user.dto.InviteUserResponse;
import com.supportplatform.user.dto.UserSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserSummaryResponse me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return UserSummaryResponse.from(userService.getWithinTenant(principal.getTenantId(), principal.getUserId()));
    }

    @GetMapping
    public List<UserSummaryResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return userService.listByTenant(principal.getTenantId()).stream()
                .map(UserSummaryResponse::from)
                .toList();
    }

    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteUserResponse invite(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                      @Valid @RequestBody InviteUserRequest request) {
        User invited = userService.invite(principal.getTenantId(), principal.getRole(), request);
        return InviteUserResponse.from(invited);
    }

    @PatchMapping("/{id}/role")
    public UserSummaryResponse changeRole(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                           @PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        User updated = userService.changeRole(principal.getTenantId(), principal.getRole(), id, request.role());
        return UserSummaryResponse.from(updated);
    }

    @PostMapping("/{id}/disable")
    public UserSummaryResponse disable(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        User updated = userService.disable(principal.getTenantId(), principal.getRole(), id);
        return UserSummaryResponse.from(updated);
    }

    @PostMapping("/{id}/enable")
    public UserSummaryResponse enable(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        User updated = userService.enable(principal.getTenantId(), principal.getRole(), id);
        return UserSummaryResponse.from(updated);
    }
}
