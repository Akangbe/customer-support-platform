package com.supportplatform.auth.passwordreset;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import com.supportplatform.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration TOKEN_TTL = Duration.ofHours(1);
    static final int MAX_REQUESTS_PER_HOUR = 3;

    /** BCrypt reads this many bytes and the encoder refuses more. */
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository, UserRepository userRepository,
                                  PasswordEncoder passwordEncoder, ApplicationEventPublisher eventPublisher) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Sends a link if this is an active account, and otherwise does nothing.
     * A pending user still has their invite, and a disabled user must not get
     * back in this way. A new link cancels any earlier one.
     */
    @Transactional
    public void request(String email) {
        Optional<User> found = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();

        Instant now = Instant.now();
        if (tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(1)))
                >= MAX_REQUESTS_PER_HOUR) {
            log.info("Password reset request ignored for user {}: hourly limit reached", user.getId());
            return;
        }

        tokenRepository.cancelOutstanding(user.getId(), now);
        String token = newToken();
        Instant expiresAt = now.plus(TOKEN_TTL);
        tokenRepository.save(new PasswordResetToken(user.getId(), hash(token), now, expiresAt));

        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(),
                AuditAction.PASSWORD_RESET_REQUESTED, "USER", user.getId(), "Requested a password reset"));
        eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), token, TOKEN_TTL));
    }

    /**
     * Sets the new password. The caller then ends the user's other sessions,
     * which has to wait until this transaction has committed.
     */
    @Transactional
    public User confirm(String token, String newPassword) {
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That password is too long. Use a shorter one.");
        }

        Instant now = Instant.now();
        String tokenHash = hash(token);
        if (tokenRepository.claim(tokenHash, now) == 0) {
            throw invalidLink();
        }
        User user = tokenRepository.findByTokenHash(tokenHash)
                .flatMap(claimed -> userRepository.findById(claimed.getUserId()))
                // Disabled after the link was sent: the link no longer opens anything.
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(PasswordResetService::invalidLink);

        user.changePassword(passwordEncoder.encode(newPassword));
        tokenRepository.cancelOutstanding(user.getId(), now);

        eventPublisher.publishEvent(new AuditEvent(user.getTenantId(), user.getId(),
                AuditAction.PASSWORD_RESET_COMPLETED, "USER", user.getId(), "Reset their password"));
        eventPublisher.publishEvent(new PasswordChangedEvent(user.getEmail()));
        return user;
    }

    @Scheduled(fixedDelay = 86_400_000)
    @Transactional
    public void deleteOldTokens() {
        tokenRepository.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
    }

    private static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This reset link is invalid or has expired. Request a new one.");
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A plain digest is enough here: the token is 256 random bits, so there is nothing to guess offline. */
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
