package com.supportplatform.message;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.message.dto.MessageResponse;
import com.supportplatform.message.dto.SendMessageRequest;
import com.supportplatform.storage.AttachmentService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;
    private final AttachmentService attachmentService;

    public MessageController(MessageService messageService, AttachmentService attachmentService) {
        this.messageService = messageService;
        this.attachmentService = attachmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                 @PathVariable UUID conversationId,
                                 @Valid @RequestBody SendMessageRequest request) {
        Message message = messageService.sendOutbound(principal.getTenantId(), conversationId,
                principal.getUserId(), request.body(), request.templateName(), request.templateLanguageCode(),
                request.templateParams(), request.attachmentId());
        return MessageResponse.from(message, request.attachmentId());
    }

    @GetMapping
    public Page<MessageResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                       @PathVariable UUID conversationId,
                                       @PageableDefault(size = 20) Pageable pageable) {
        Page<Message> messages = messageService.listForConversation(principal.getTenantId(), conversationId, pageable);
        List<UUID> messageIds = messages.getContent().stream().map(Message::getId).toList();
        Map<UUID, UUID> attachmentIdsByMessageId = attachmentService.findAttachmentIdsByMessageIds(messageIds);
        return messages.map(message -> MessageResponse.from(message, attachmentIdsByMessageId.get(message.getId())));
    }
}
