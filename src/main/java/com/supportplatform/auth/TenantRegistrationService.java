package com.supportplatform.auth;

import com.supportplatform.tenant.Tenant;
import com.supportplatform.tenant.TenantRepository;
import com.supportplatform.user.EmailAlreadyRegisteredException;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Creates a new tenant and its first user (the Owner) in one transaction.
 * This is the only way a tenant comes into existence in v1 — see
 * identity-and-access.md §2.
 */
@Service
public class TenantRegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantRegistrationService(TenantRepository tenantRepository, UserRepository userRepository,
                                      PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User registerTenant(String tenantName, String ownerName, String rawEmail, String rawPassword) {
        String email = rawEmail.toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        Tenant tenant = new Tenant(tenantName, generateUniqueSlug(tenantName));
        tenantRepository.save(tenant);

        User owner = User.createOwner(tenant.getId(), email, ownerName, passwordEncoder.encode(rawPassword));
        return userRepository.save(owner);
    }

    private String generateUniqueSlug(String tenantName) {
        String base = tenantName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "tenant";
        }

        String candidate = base;
        while (tenantRepository.existsBySlug(candidate)) {
            candidate = base + "-" + Integer.toHexString(RANDOM.nextInt(0xFFFF));
        }
        return candidate;
    }
}
