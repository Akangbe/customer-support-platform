package com.supportplatform.client;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.UserStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a session into a {@link ClientScope}. The user is re-read on every
 * call because the session holds a copy taken at login: without this, a
 * client user who is disabled would keep reading until the session expired.
 */
@Component
public class ClientScopeResolver {

    private final UserRepository userRepository;

    public ClientScopeResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ClientScope resolve(AuthenticatedPrincipal principal) {
        User user = userRepository.findByIdAndTenantId(principal.getUserId(), principal.getTenantId())
                .filter(found -> found.getRole() == UserRole.CLIENT && found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("This account no longer has client access"));
        return new ClientScope(user.getId(), user.getTenantId(), user.getClientAccountId());
    }
}
