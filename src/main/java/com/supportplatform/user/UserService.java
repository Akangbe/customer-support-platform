package com.supportplatform.user;

import com.supportplatform.user.dto.InviteUserRequest;
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
 * {@code findByIdAndTenantId}.
 */
@Service
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITE_TOKEN_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User invite(UUID actingTenantId, UserRole actingRole, InviteUserRequest request) {
        requireCanManage(actingRole, request.role());

        String email = request.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        String token = generateToken();
        User user = User.createInvited(actingTenantId, email, request.name(), request.role(),
                token, Instant.now().plus(INVITE_TOKEN_TTL));
        return userRepository.save(user);
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
    public User changeRole(UUID actingTenantId, UserRole actingRole, UUID targetUserId, UserRole newRole) {
        User target = getWithinTenant(actingTenantId, targetUserId);
        requireCanManage(actingRole, target.getRole());
        requireCanManage(actingRole, newRole);

        if (newRole != UserRole.OWNER) {
            guardLastOwner(target);
        }
        target.changeRole(newRole);
        return target;
    }

    @Transactional
    public User disable(UUID actingTenantId, UserRole actingRole, UUID targetUserId) {
        User target = getWithinTenant(actingTenantId, targetUserId);
        requireCanManage(actingRole, target.getRole());

        guardLastOwner(target);
        target.disable();
        return target;
    }

    @Transactional
    public User enable(UUID actingTenantId, UserRole actingRole, UUID targetUserId) {
        User target = getWithinTenant(actingTenantId, targetUserId);
        requireCanManage(actingRole, target.getRole());

        target.enable();
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

    private String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
