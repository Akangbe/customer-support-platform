package com.supportplatform.conversation;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.conversation.dto.AssignConversationRequest;
import com.supportplatform.conversation.dto.ConversationResponse;
import com.supportplatform.conversation.dto.StartConversationRequest;
import com.supportplatform.conversation.dto.UpdatePriorityRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ConversationResponse start(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                       @Valid @RequestBody StartConversationRequest request) {
        Conversation conversation = conversationService.findOrOpenForCustomer(principal.getTenantId(), request.customerId());
        return ConversationResponse.from(conversation);
    }

    @GetMapping
    public Page<ConversationResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                            @RequestParam(required = false) ConversationStatus status,
                                            @PageableDefault(size = 20) Pageable pageable) {
        return conversationService.listByTenant(principal.getTenantId(), status, pageable).map(ConversationResponse::from);
    }

    @GetMapping("/{id}")
    public ConversationResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return ConversationResponse.from(conversationService.getWithinTenant(principal.getTenantId(), id));
    }

    @PostMapping("/{id}/assign")
    public ConversationResponse assign(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id,
                                        @Valid @RequestBody AssignConversationRequest request) {
        Conversation conversation = conversationService.assign(principal.getTenantId(), principal.getUserId(),
                principal.getRole(), id, request.agentId());
        return ConversationResponse.from(conversation);
    }

    @PostMapping("/{id}/unassign")
    public ConversationResponse unassign(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        Conversation conversation = conversationService.unassign(principal.getTenantId(), principal.getUserId(),
                principal.getRole(), id);
        return ConversationResponse.from(conversation);
    }

    @PostMapping("/{id}/close")
    public ConversationResponse close(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return ConversationResponse.from(conversationService.close(principal.getTenantId(), id));
    }

    @PostMapping("/{id}/reopen")
    public ConversationResponse reopen(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return ConversationResponse.from(conversationService.reopen(principal.getTenantId(), id));
    }

    @PatchMapping("/{id}/priority")
    public ConversationResponse updatePriority(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id,
                                                @Valid @RequestBody UpdatePriorityRequest request) {
        Conversation conversation = conversationService.updatePriority(principal.getTenantId(), id, request.priority());
        return ConversationResponse.from(conversation);
    }
}
