package com.supportplatform.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);

    List<User> findAllByTenantId(UUID tenantId);

    Optional<User> findByInviteToken(String inviteToken);

    long countByTenantIdAndRoleAndStatus(UUID tenantId, UserRole role, UserStatus status);

    /** The people who can act on a workspace-level problem — see {@code DeliveryBlockedEmailListener}. */
    List<User> findAllByTenantIdAndRoleInAndStatus(UUID tenantId, Collection<UserRole> roles, UserStatus status);
}
