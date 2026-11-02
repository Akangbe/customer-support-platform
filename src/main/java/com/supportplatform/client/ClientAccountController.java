package com.supportplatform.client;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.client.dto.ClientAccountResponse;
import com.supportplatform.client.dto.InviteClientUserRequest;
import com.supportplatform.user.UserService;
import com.supportplatform.user.dto.InviteUserResponse;
import com.supportplatform.user.dto.UserSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * How a tenant's Owner or Admin manages its client accounts and gives people
 * at those businesses a login. Not the client's own API, which is
 * {@link ClientPortalController}.
 */
@RestController
@RequestMapping("/api/v1/client-accounts")
public class ClientAccountController {

    private final UserService userService;

    public ClientAccountController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<ClientAccountResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return userService.listClientAccounts(principal.getTenantId(), principal.getRole()).stream()
                .map(ClientAccountResponse::from)
                .toList();
    }

    @GetMapping("/{clientAccountId}/users")
    public List<UserSummaryResponse> listUsers(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                 @PathVariable UUID clientAccountId) {
        return userService.listClientUsers(principal.getTenantId(), principal.getRole(), clientAccountId).stream()
                .map(UserSummaryResponse::from)
                .toList();
    }

    @PostMapping("/{clientAccountId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteUserResponse inviteUser(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                           @PathVariable UUID clientAccountId,
                                           @Valid @RequestBody InviteClientUserRequest request) {
        return InviteUserResponse.from(userService.inviteClient(principal.getTenantId(), principal.getUserId(),
                principal.getRole(), clientAccountId, request.email(), request.name()));
    }
}
