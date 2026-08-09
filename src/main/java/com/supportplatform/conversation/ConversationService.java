package com.supportplatform.conversation;

import com.supportplatform.customer.CustomerService;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Conversation lifecycle and assignment (conversation-domain.md). Tenant
 * scoping is enforced here, not trusted from the caller — every lookup
 * goes through {@code findByIdAndTenantId}.
 */
@Service
public class ConversationService {

    private static final List<ConversationStatus> ACTIVE_STATUSES = List.of(ConversationStatus.OPEN, ConversationStatus.ASSIGNED);

    private final ConversationRepository conversationRepository;
    private final CustomerService customerService;
    private final UserRepository userRepository;

    public ConversationService(ConversationRepository conversationRepository, CustomerService customerService,
                                UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.customerService = customerService;
        this.userRepository = userRepository;
    }

    /**
     * Idempotent (ADR-013): reuses the customer's active conversation if
     * one exists, else reopens their most recent closed one, else starts a
     * fresh one. This is what an agent starting a conversation manually
     * calls today, and what Phase 6's WhatsApp webhook will call on every
     * inbound message.
     */
    @Transactional
    public Conversation findOrOpenForCustomer(UUID tenantId, UUID customerId) {
        customerService.getWithinTenant(tenantId, customerId); // 404s if the customer doesn't exist in this tenant

        return conversationRepository.findFirstByTenantIdAndCustomerIdAndStatusIn(tenantId, customerId, ACTIVE_STATUSES)
                .orElseGet(() -> conversationRepository.findFirstByTenantIdAndCustomerIdAndStatusOrderByClosedAtDesc(
                                tenantId, customerId, ConversationStatus.CLOSED)
                        .map(c -> {
                            c.reopen();
                            return c;
                        })
                        .orElseGet(() -> conversationRepository.save(new Conversation(tenantId, customerId))));
    }

    @Transactional(readOnly = true)
    public Conversation getWithinTenant(UUID tenantId, UUID conversationId) {
        return conversationRepository.findByIdAndTenantId(conversationId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Conversation not found"));
    }

    @Transactional(readOnly = true)
    public Page<Conversation> listByTenant(UUID tenantId, ConversationStatus statusFilter, Pageable pageable) {
        return statusFilter == null
                ? conversationRepository.findAllByTenantId(tenantId, pageable)
                : conversationRepository.findAllByTenantIdAndStatus(tenantId, statusFilter, pageable);
    }

    /** Self-claim allowed for anyone; reassigning something already claimed needs Owner/Admin/Manager (ADR-017). */
    @Transactional
    public Conversation assign(UUID tenantId, UUID actingUserId, UserRole actingRole, UUID conversationId, UUID targetAgentId) {
        Conversation conversation = getWithinTenant(tenantId, conversationId);

        boolean targetIsSelf = targetAgentId.equals(actingUserId);
        boolean currentlyUnassignedOrSelf = conversation.getAssignedAgentId() == null
                || conversation.getAssignedAgentId().equals(actingUserId);
        boolean isSelfClaim = targetIsSelf && currentlyUnassignedOrSelf;

        if (!isSelfClaim && !isPrivileged(actingRole)) {
            throw new AccessDeniedException("Only Owner, Admin, or Manager can reassign a conversation");
        }

        User target = userRepository.findByIdAndTenantId(targetAgentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Agent not found"));
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidConversationStateException("Cannot assign to an inactive user");
        }

        conversation.assign(targetAgentId);
        return conversation;
    }

    /** The current assignee may release their own claim; so can Owner/Admin/Manager. */
    @Transactional
    public Conversation unassign(UUID tenantId, UUID actingUserId, UserRole actingRole, UUID conversationId) {
        Conversation conversation = getWithinTenant(tenantId, conversationId);

        boolean isSelf = actingUserId.equals(conversation.getAssignedAgentId());
        if (!isSelf && !isPrivileged(actingRole)) {
            throw new AccessDeniedException("Only the assigned agent, Owner, Admin, or Manager can unassign a conversation");
        }

        conversation.unassign();
        return conversation;
    }

    @Transactional
    public Conversation close(UUID tenantId, UUID conversationId) {
        Conversation conversation = getWithinTenant(tenantId, conversationId);
        conversation.close(Instant.now());
        return conversation;
    }

    @Transactional
    public Conversation reopen(UUID tenantId, UUID conversationId) {
        Conversation conversation = getWithinTenant(tenantId, conversationId);
        conversation.reopen();
        return conversation;
    }

    @Transactional
    public Conversation updatePriority(UUID tenantId, UUID conversationId, ConversationPriority priority) {
        Conversation conversation = getWithinTenant(tenantId, conversationId);
        conversation.changePriority(priority);
        return conversation;
    }

    private boolean isPrivileged(UserRole role) {
        return role == UserRole.OWNER || role == UserRole.ADMIN || role == UserRole.MANAGER;
    }
}
