package com.supportplatform.auth.passwordreset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /**
     * Marks a live token used and returns how many rows changed. One update
     * decides it, so two requests racing with the same link cannot both win.
     */
    @Modifying
    @Query(value = """
            UPDATE password_reset_token
               SET used_at = :now
             WHERE token_hash = :tokenHash
               AND used_at IS NULL
               AND expires_at > :now
            """, nativeQuery = true)
    int claim(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE password_reset_token
               SET used_at = :now
             WHERE user_id = :userId
               AND used_at IS NULL
            """, nativeQuery = true)
    int cancelOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(value = "DELETE FROM password_reset_token WHERE expires_at < :cutoff", nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
