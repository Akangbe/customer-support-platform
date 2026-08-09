package com.supportplatform.message;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.message.dto.MessageResponse;
import com.supportplatform.message.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                 @PathVariable UUID conversationId,
                                 @Valid @RequestBody SendMessageRequest request) {
        Message message = messageService.sendOutbound(principal.getTenantId(), conversationId,
                principal.getUserId(), request.body(), request.templateName(), request.templateLanguageCode(),
                request.templateParams());
        return MessageResponse.from(message);
    }

    @GetMapping
    public Page<MessageResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                       @PathVariable UUID conversationId,
                                       @PageableDefault(size = 20) Pageable pageable) {
        return messageService.listForConversation(principal.getTenantId(), conversationId, pageable)
                .map(MessageResponse::from);
    }
}
