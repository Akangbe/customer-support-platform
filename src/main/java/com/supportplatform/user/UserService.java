package com.supportplatform.user;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.client.ClientAccount;
import com.supportplatform.client.ClientAccountRepository;
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

import static org.springframework.http.HttpStatus.BAD_REQUEST;
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
    private final ClientAccountRepository clientAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository, ClientAccountRepository clientAccountRepository,
                        PasswordEncoder passwordEncoder, ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public User invite(UUID actingTenantId, UUID actorUserId, UserRole actingRole, InviteUserRequest request) {
        requireStaffRole(request.role());
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

    /** Gives a person at a client business a login tied to that client. Owner or Admin only. */
    @Transactional
    public User inviteClient(UUID actingTenantId, UUID actorUserId, UserRole actingRole, UUID clientAccountId,
                              String rawEmail, String name) {
        requireOwnerOrAdmin(actingRole);
        requireClientAccount(actingTenantId, clientAccountId);

        String email = rawEmail.toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        User user = userRepository.save(User.createInvitedClient(actingTenantId, clientAccountId, email, name,
                generateToken(), Instant.now().plus(INVITE_TOKEN_TTL)));

        publish(actingTenantId, actorUserId, AuditAction.USER_INVITED, user.getId(),
                "Invited " + email + " to client account " + clientAccountId);
        eventPublisher.publishEvent(new UserInvitedEvent(actingTenantId, user.getId()));
        return user;
    }

    @Transactional(readOnly = true)
    public List<ClientAccount> listClientAccounts(UUID tenantId, UserRole actingRole) {
        requireOwnerOrAdmin(actingRole);
        return clientAccountRepository.findByTenantIdOrderByName(tenantId);
    }

    @Transactional(readOnly = true)
    public List<User> listClientUsers(UUID tenantId, UserRole actingRole, UUID clientAccountId) {
        requireOwnerOrAdmin(actingRole);
        requireClientAccount(tenantId, clientAccountId);
        return userRepository.findAllByTenantIdAndClientAccountId(tenantId, clientAccountId);
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
        return userRepository.findAllByTenantIdAndRoleNot(tenantId, UserRole.CLIENT);
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
        requireStaffRole(target.getRole());
        requireStaffRole(newRole);
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

    /** Admin may manage the operational roles and client users; Owner may manage any role. */
    private void requireCanManage(UserRole actingRole, UserRole targetRole) {
        boolean allowed = switch (actingRole) {
            case OWNER -> true;
            case ADMIN -> targetRole == UserRole.MANAGER || targetRole == UserRole.AGENT
                    || targetRole == UserRole.CLIENT;
            case MANAGER, AGENT, CLIENT -> false;
        };
        if (!allowed) {
            throw new AccessDeniedException("Insufficient privileges to manage this role");
        }
    }

    /** CLIENT is never granted or removed by a role change: it comes with a client account, fixed at invite. */
    private void requireStaffRole(UserRole role) {
        if (role == UserRole.CLIENT) {
            throw new ResponseStatusException(BAD_REQUEST, "Client users are managed through their client account.");
        }
    }

    private void requireOwnerOrAdmin(UserRole actingRole) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can manage client accounts");
        }
    }

    private void requireClientAccount(UUID tenantId, UUID clientAccountId) {
        clientAccountRepository.findByIdAndTenantId(clientAccountId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Client account not found"));
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
