package com.supportplatform.storage;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.storage.dto.AttachmentResponse;
import com.supportplatform.storage.dto.AttachmentUrlResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Upload is any authenticated tenant member — attaching a file isn't a more privileged act than typing a caption (storage-domain.md §4). */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                      @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "file must not be empty");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to read uploaded file");
        }

        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        Attachment attachment = attachmentService.upload(principal.getTenantId(), content, contentType, file.getOriginalFilename());
        return AttachmentResponse.from(attachment);
    }

    @GetMapping("/{id}")
    public AttachmentUrlResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        Attachment attachment = attachmentService.getWithinTenant(principal.getTenantId(), id);
        URI url = attachmentService.presignedUrlFor(attachment);
        return new AttachmentUrlResponse(url.toString(), Instant.now().plus(attachmentService.getPresignedUrlTtl()));
    }
}
