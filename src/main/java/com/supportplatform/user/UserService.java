package com.supportplatform.user;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.user.dto.InviteUserRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Owns user-management business rules: the Owner/Admin privilege boundary
 * and the last-Owner invariant (ADR-016). Tenant scoping is enforced here,
 * not trusted from the caller — every lookup goes through
 * {@code findByIdAndTenantId}. Every method here is an "administrative
 * action" per FR-AUD-001, so each publishes an {@link AuditEvent}
 * (audit-domain.md §1) alongside its result.
 */
@Service
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITE_TOKEN_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public User invite(UUID actingTenantId, UUID actorUserId, UserRole actingRole, InviteUserRequest request) {
        requireCanManage(actingRole, request.role());

        String email = request.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        String token = generateToken();
        User user = User.createInvited(actingTenantId, email, request.name(), request.role(),
                token, Instant.now().plus(INVITE_TOKEN_TTL));
        user = userRepository.save(user);

        publish(actingTenantId, actorUserId, AuditAction.USER_INVITED, user.getId(),
                "Invited " + email + " as " + request.role());
        eventPublisher.publishEvent(new UserInvitedEvent(actingTenantId, user.getId()));
        return user;
    }

    @Transactional
    public User acceptInvite(String token, String rawPassword) {
        User user = userRepository.findByInviteToken(token)
                .filter(u -> u.isInviteTokenValid(token, Instant.now()))
                .orElseThrow(InvalidInviteTokenException::new);

        user.activate(passwordEncoder.encode(rawPassword));
        return user;
    }

    @Transactional(readOnly = true)
    public List<User> listByTenant(UUID tenantId) {
        return userRepository.findAllByTenantId(tenantId);
    }

    @Transactional
    public void recordLogin(UUID tenantId, UUID userId) {
        getWithinTenant(tenantId, userId).recordLogin(Instant.now());
    }

    @Transactional(readOnly = true)
    public User getWithinTenant(UUID tenantId, UUID userId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }

    @Transactional
    public User changeRole(UUID actingTenantId, UUID actorUserId, UserRole actingRole, UUID targetUserId, UserRole newRole) {
        User target = getWithinTenant(actingTenantId, targetUserId);
        requireCanManage(actingRole, target.getRole());
        requireCanManage(actingRole, newRole);

        if (newRole != UserRole.OWNER) {
            guardLastOwner(target);
        }
        UserRole previousRole = target.getRole();
        target.changeRole(newRole);

        publish(actingTenantId, actorUserId, AuditAction.USER_ROLE_CHANGED, target.getId(),
                "Changed role from " + previousRole + " to " + newRole);
        return target;
    }

    @Transactional
    public User disable(UUID actingTenantId, UUID actorUserId, UserRole actingRole, UUID targetUserId) {
        User target = getWithinTenant(actingTenantId, targetUserId);
        requireCanManage(actingRole, target.getRole());

        guardLastOwner(target);
        target.disable();

        publish(actingTenantId, actorUserId, AuditAction.USER_DISABLED, target.getId(), "Disabled " + target.getEmail());
        return target;
    }

    @Transactional
    public User enable(UUID actingTenantId, UUID actorUserId, UserRole actingRole, UUID targetUserId) {
        User target = getWithinTenant(actingTenantId, targetUserId);
        requireCanManage(actingRole, target.getRole());

        target.enable();

        publish(actingTenantId, actorUserId, AuditAction.USER_ENABLED, target.getId(), "Enabled " + target.getEmail());
        return target;
    }

    /** Admin may manage only the operational roles (Manager, Agent); Owner may manage any role. */
    private void requireCanManage(UserRole actingRole, UserRole targetRole) {
        boolean allowed = switch (actingRole) {
            case OWNER -> true;
            case ADMIN -> targetRole == UserRole.MANAGER || targetRole == UserRole.AGENT;
            case MANAGER, AGENT -> false;
        };
        if (!allowed) {
            throw new AccessDeniedException("Insufficient privileges to manage this role");
        }
    }

    /** A tenant may never be left with zero active Owners (ADR-016). */
    private void guardLastOwner(User target) {
        if (target.getRole() != UserRole.OWNER || target.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        long activeOwners = userRepository.countByTenantIdAndRoleAndStatus(target.getTenantId(), UserRole.OWNER, UserStatus.ACTIVE);
        if (activeOwners <= 1) {
            throw new LastOwnerException();
        }
    }

    private void publish(UUID tenantId, UUID actorUserId, AuditAction action, UUID targetUserId, String detail) {
        eventPublisher.publishEvent(new AuditEvent(tenantId, actorUserId, action, "USER", targetUserId, detail));
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
