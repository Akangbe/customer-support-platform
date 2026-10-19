package com.supportplatform.email;

import com.supportplatform.apikey.ApiKey;
import com.supportplatform.apikey.ApiKeyRepository;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.UserStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about an API key's traffic: the tenant's own Owners and
 * Admins, plus the key's contact when one was recorded (V16).
 *
 * <p>Shared by the volume alert, the daily spend summary and the
 * delivery-blocked alert so they can never disagree about who is told.
 */
@Component
public class ApiKeyAlertRecipients {

    private static final Set<UserRole> ALERTABLE_ROLES = Set.of(UserRole.OWNER, UserRole.ADMIN);

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAlertRecipients(UserRepository userRepository, ApiKeyRepository apiKeyRepository) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * A {@link LinkedHashSet} because an integrator who is also a user of
     * the tenant should get one mail, not two, and because the tenant's own
     * people should be first in the list.
     */
    public List<String> forKey(UUID tenantId, UUID apiKeyId) {
        Set<String> unique = new LinkedHashSet<>(ownersAndAdmins(tenantId));
        key(tenantId, apiKeyId).ifPresent(key -> addContacts(unique, key.getContactEmail()));
        return new ArrayList<>(unique);
    }

    /**
     * Who hears about the tenant's WhatsApp number as a whole: its Owners
     * and Admins, plus the contact of every active key. A block on the
     * number stops every key's sends at once, so every integrator sending
     * through it is told, not only the tenant's own people.
     */
    public List<String> forAllKeys(UUID tenantId) {
        Set<String> unique = new LinkedHashSet<>(ownersAndAdmins(tenantId));
        apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(ApiKey::isActive)
                .forEach(key -> addContacts(unique, key.getContactEmail()));
        return new ArrayList<>(unique);
    }

    /** The tenant's own active Owners and Admins, without the key's contact. */
    public List<String> ownersAndAdmins(UUID tenantId) {
        return userRepository.findAllByTenantIdAndRoleInAndStatus(tenantId, ALERTABLE_ROLES, UserStatus.ACTIVE)
                .stream()
                .map(User::getEmail)
                .toList();
    }

    /** The key's name, for naming it in a mail; empty if the key is not this tenant's. */
    public Optional<String> keyName(UUID tenantId, UUID apiKeyId) {
        return key(tenantId, apiKeyId).map(ApiKey::getName);
    }

    /**
     * The column may hold several addresses (see ApiKeyService); each
     * becomes its own recipient, never one comma-joined string, which SES
     * would reject as a single malformed address.
     */
    private static void addContacts(Set<String> recipients, String contacts) {
        if (contacts == null || contacts.isBlank()) {
            return;
        }
        for (String address : contacts.split("[,;]")) {
            if (!address.isBlank()) {
                recipients.add(address.trim());
            }
        }
    }

    private Optional<ApiKey> key(UUID tenantId, UUID apiKeyId) {
        if (apiKeyId == null) {
            return Optional.empty();
        }
        return apiKeyRepository.findById(apiKeyId)
                // Tenant-scoped even on a lookup by primary key (Rule 3): an
                // id that belongs to another tenant must not resolve here.
                .filter(key -> key.getTenantId().equals(tenantId));
    }
}
